package me.monstermazeai.minecraft.v18;

import me.monstermazeai.adapter.LegacyAction;
import me.monstermazeai.adapter.LegacyProtocol;
import me.monstermazeai.adapter.LegacyWorldObservation;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Java-8 Minecraft-side process bridge.
 *
 * The sidecar is deliberately treated as a runtime dependency rather than an
 * implicit developer-machine setting. Explicit property/environment overrides
 * remain supported, but release/dev adapter jars can carry the runtime sidecar
 * as an embedded resource and extract it automatically.
 */
public final class Minecraft18AiRuntime {
    private static final String EMBEDDED_RUNTIME_RESOURCE =
            "/runtime/monster-maze-ai-runtime.jar";
    private static final long MAX_ACTION_AGE_TICKS = 40L;

    private volatile Process process;
    private volatile DataInputStream input;
    private volatile DataOutputStream output;
    private LegacyAction lastAction = LegacyAction.IDLE;
    private long decideCount;

    private final ExecutorService decisionExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "MonsterMazeAI-1.8-planner");
        thread.setDaemon(true);
        return thread;
    });

    private Future<?> pendingDecision;
    private LegacyWorldObservation latestObservation;
    private DecisionResult latestCompletedDecision;
    private long lastSubmittedTick = Long.MIN_VALUE;
    private long lastCompletedTick = Long.MIN_VALUE;
    private boolean lastCompletedWasStaleTurn;

    private String resolvedRuntimeJar;
    private String runtimeResolutionDetail = "UNRESOLVED";
    private boolean runtimeResolutionAttempted;
    private boolean configurationLogged;
    private boolean unavailableLogged;

    public synchronized boolean configured() {
        return runtimeJar() != null;
    }

    /**
     * Starts the sidecar if available. This method is intentionally verbose:
     * a missing runtime must never look like an AI that simply "did nothing".
     */
    public synchronized void startIfConfigured() {
        if (process != null) {
            if (process.isAlive()) return;
            System.err.println("[MonsterMazeAI/1.8] RUNTIME previous sidecar is no longer alive; restarting");
            closeProcess();
        }

        String jar = runtimeJar();
        String java = javaExecutable();

        if (!configurationLogged) {
            configurationLogged = true;
            System.out.println("[MonsterMazeAI/1.8] RUNTIME CONFIG " + runtimeStatus());
        }

        if (jar == null) {
            logUnavailableOnce("No runtime sidecar was found. "
                    + "Set MONSTERMAZE_AI_RUNTIME_JAR/monstermazeai.runtime.jar or rebuild the adapter "
                    + "after the common runtime jar is available.");
            return;
        }
        if (java == null || java.trim().isEmpty()) {
            logUnavailableOnce("No Java executable could be resolved");
            return;
        }

        Path jarPath = Paths.get(jar);
        if (!Files.isRegularFile(jarPath)) {
            logUnavailableOnce("Resolved runtime jar does not exist: " + jarPath.toAbsolutePath());
            return;
        }

        try {
            List<String> command = new ArrayList<String>();
            command.add(java);
            command.add("-jar");
            command.add(jarPath.toAbsolutePath().toString());

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            process = builder.start();
            input = new DataInputStream(new BufferedInputStream(process.getInputStream()));
            output = new DataOutputStream(new BufferedOutputStream(process.getOutputStream()));
            lastAction = LegacyAction.IDLE;
            decideCount = 0;
            unavailableLogged = false;

            System.out.println("[MonsterMazeAI/1.8] RUNTIME STARTED java=" + java
                    + " jar=" + jarPath.toAbsolutePath());
        } catch (IOException failure) {
            closeProcess();
            logUnavailableOnce("Sidecar start failed: "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        } catch (RuntimeException failure) {
            closeProcess();
            logUnavailableOnce("Sidecar start failed: "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    /**
     * Publish the newest observation without queueing obsolete world states.
     * A planner worker consumes the latest snapshot and, when it finishes, keeps
     * processing any newer snapshot that arrived while it was computing.
     */
    public synchronized void submit(LegacyWorldObservation observation) {
        if (observation == null) return;

        if (process == null || !process.isAlive() || output == null || input == null) {
            startIfConfigured();
        }
        if (process == null || !process.isAlive() || output == null || input == null) {
            logUnavailableOnce("Observation dropped because runtime is unavailable");
            return;
        }

        latestObservation = observation;
        if (pendingDecision == null || pendingDecision.isDone()) {
            pendingDecision = decisionExecutor.submit(this::processLatestObservations);
        }
    }

    /**
     * Poll the newest completed decision without ever blocking the Minecraft
     * client thread. Obsolete completed decisions are naturally overwritten by
     * newer planner output.
     */
    public synchronized LegacyAction pollCompleted(long currentTick) {
        DecisionResult result = latestCompletedDecision;
        latestCompletedDecision = null;
        if (result == null || result.action == null) return null;

        long age = currentTick - result.tick;
        lastCompletedWasStaleTurn = false;
        if (age > MAX_ACTION_AGE_TICKS) {
            /*
             * A delayed per-tick command must never be held as though it were
             * fresh. The only stale command we allow through is a pure bounded
             * turn. Movement, jump and ability commands remain fail-closed until
             * a fresh observation has been planned.
             */
            if (isSafeStaleTurn(result.action)) {
                lastCompletedWasStaleTurn = true;
                lastCompletedTick = result.tick;
                return result.action;
            }
            lastCompletedTick = result.tick;
            return LegacyAction.IDLE;
        }

        lastCompletedTick = result.tick;
        return result.action;
    }

    /** Worker loop that always consumes the newest available observation. */
    private void processLatestObservations() {
        while (true) {
            LegacyWorldObservation submitted;
            synchronized (this) {
                submitted = latestObservation;
                latestObservation = null;
                if (submitted == null) {
                    pendingDecision = null;
                    return;
                }
                lastSubmittedTick = submitted.worldTick;
            }

            LegacyAction action;
            try {
                action = decide(submitted);
            } catch (RuntimeException failure) {
                System.err.println("[MonsterMazeAI/1.8] RUNTIME ASYNC DECISION FAILED: "
                        + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                failure.printStackTrace(System.err);
                action = LegacyAction.IDLE;
            }

            synchronized (this) {
                latestCompletedDecision = new DecisionResult(submitted.worldTick, action);
                if (latestObservation == null) {
                    pendingDecision = null;
                    return;
                }
            }
        }
    }

    public synchronized boolean lastCompletedWasStaleTurn() {
        return lastCompletedWasStaleTurn;
    }

    public synchronized boolean decisionPending() {
        return latestObservation != null || (pendingDecision != null && !pendingDecision.isDone());
    }

    public LegacyAction decide(LegacyWorldObservation observation) {
        if (observation == null) {
            System.err.println("[MonsterMazeAI/1.8] RUNTIME DECIDE(null) -> IDLE");
            return LegacyAction.IDLE;
        }

        if (process == null || !process.isAlive()) startIfConfigured();
        if (process == null || !process.isAlive() || output == null || input == null) {
            System.err.println("[MonsterMazeAI/1.8] RUNTIME DECIDE unavailable -> IDLE");
            return LegacyAction.IDLE;
        }

        try {
            decideCount++;
            if (decideCount == 1 || decideCount % 20 == 0) {
                System.err.println("[MonsterMazeAI/1.8] RUNTIME SEND#"
                        + decideCount + " tick=" + observation.worldTick
                        + " inMaze=" + observation.inMonsterMaze
                        + " detected=" + observation.mazeDetected
                        + " pad=" + (observation.pad == null ? "null"
                            : observation.pad.row + "," + observation.pad.column
                              + " reached=" + observation.pad.reached));
            }

            LegacyProtocol.writeObservation(output, observation);
            output.flush();

            LegacyAction action = LegacyProtocol.readAction(input);
            lastAction = action == null ? LegacyAction.IDLE : action;

            if (decideCount == 1 || decideCount % 20 == 0
                    || lastAction.forward != 0.0f || lastAction.strafe != 0.0f
                    || lastAction.jump || lastAction.yawDelta != 0.0f) {
                System.err.println("[MonsterMazeAI/1.8] RUNTIME RECV#"
                        + decideCount + " tick=" + observation.worldTick
                        + " action=" + describe(lastAction));
            }
            return lastAction;
        } catch (EOFException end) {
            System.err.println("[MonsterMazeAI/1.8] RUNTIME SIDECAR EXITED; returning IDLE");
            closeProcess();
            return LegacyAction.IDLE;
        } catch (IOException failure) {
            System.err.println("[MonsterMazeAI/1.8] RUNTIME I/O FAILED: "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            failure.printStackTrace(System.err);
            closeProcess();
            return LegacyAction.IDLE;
        }
    }

    public synchronized void stop() {
        closeProcess();
        decisionExecutor.shutdownNow();
        latestObservation = null;
        latestCompletedDecision = null;
        pendingDecision = null;
        lastSubmittedTick = Long.MIN_VALUE;
        lastCompletedTick = Long.MIN_VALUE;
        lastCompletedWasStaleTurn = false;
    }

    public synchronized LegacyAction lastAction() { return lastAction; }

    /**
     * Status is deliberately exposed for adapter diagnostics and tests.
     */
    public synchronized String runtimeStatus() {
        return "configured=" + (runtimeJar() != null)
                + " source=" + runtimeResolutionDetail
                + " processAlive=" + (process != null && process.isAlive())
                + " java=" + javaExecutable();
    }

    static String resolveRuntimeJar(String property, String environment) {
        if (property != null && !property.trim().isEmpty()) return property.trim();
        return environment == null || environment.trim().isEmpty() ? null : environment.trim();
    }

    private synchronized String runtimeJar() {
        if (runtimeResolutionAttempted) return resolvedRuntimeJar;
        runtimeResolutionAttempted = true;

        String explicit = resolveRuntimeJar(
                System.getProperty("monstermazeai.runtime.jar"),
                System.getenv("MONSTERMAZE_AI_RUNTIME_JAR"));
        if (explicit != null) {
            Path path = Paths.get(explicit);
            if (Files.isRegularFile(path)) {
                resolvedRuntimeJar = path.toAbsolutePath().toString();
                runtimeResolutionDetail = "explicit:" + resolvedRuntimeJar;
                return resolvedRuntimeJar;
            }
            runtimeResolutionDetail = "explicit-missing:" + path.toAbsolutePath();
        }

        Path discovered = discoverLocalRuntimeJar();
        if (discovered != null) {
            resolvedRuntimeJar = discovered.toAbsolutePath().toString();
            runtimeResolutionDetail = "discovered:" + resolvedRuntimeJar;
            return resolvedRuntimeJar;
        }

        Path embedded = extractEmbeddedRuntime();
        if (embedded != null) {
            resolvedRuntimeJar = embedded.toAbsolutePath().toString();
            runtimeResolutionDetail = "embedded:" + resolvedRuntimeJar;
            return resolvedRuntimeJar;
        }

        if (runtimeResolutionDetail.equals("UNRESOLVED")) {
            runtimeResolutionDetail = "not-found";
        }
        return null;
    }

    private Path discoverLocalRuntimeJar() {
        List<Path> candidates = new ArrayList<Path>();
        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.trim().isEmpty()) {
            Path root = Paths.get(userDir);
            addRuntimeCandidates(candidates, root);
            addRuntimeCandidates(candidates, root.resolve("..").normalize());
            addRuntimeCandidates(candidates, root.resolve("../..").normalize());
        }

        try {
            URI codeSource = Minecraft18AiRuntime.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            Path location = Paths.get(codeSource);
            Path base = Files.isDirectory(location) ? location : location.getParent();
            if (base != null) {
                addRuntimeCandidates(candidates, base);
                addRuntimeCandidates(candidates, base.resolve("..").normalize());
                addRuntimeCandidates(candidates, base.resolve("../..").normalize());
            }
        } catch (Exception ignored) {
            // Embedded runtime remains the final distribution-safe fallback.
        }

        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private static void addRuntimeCandidates(List<Path> candidates, Path base) {
        if (base == null) return;
        candidates.add(base.resolve("common/target/common-0.1.0-SNAPSHOT-runtime.jar").normalize());
        candidates.add(base.resolve("common/target/common-0.1.0-SNAPSHOT.jar").normalize());
        candidates.add(base.resolve("target/common-0.1.0-SNAPSHOT-runtime.jar").normalize());
        candidates.add(base.resolve("target/common-0.1.0-SNAPSHOT.jar").normalize());
    }

    private Path extractEmbeddedRuntime() {
        InputStream stream = Minecraft18AiRuntime.class.getResourceAsStream(EMBEDDED_RUNTIME_RESOURCE);
        if (stream == null) return null;

        try {
            Path temp = Files.createTempFile("monster-maze-ai-runtime-", ".jar");
            Files.copy(stream, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            temp.toFile().deleteOnExit();
            return temp;
        } catch (IOException failure) {
            System.err.println("[MonsterMazeAI/1.8] RUNTIME embedded extraction failed: "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            return null;
        } finally {
            try { stream.close(); } catch (IOException ignored) {}
        }
    }

    static String resolveJavaExecutable(String property, String environment, String javaHome) {
        if (property != null && !property.trim().isEmpty()) return property.trim();
        if (environment != null && !environment.trim().isEmpty()) return environment.trim();
        if (javaHome != null && !javaHome.trim().isEmpty()) {
            String slash = javaHome.indexOf('\\') >= 0 ? "\\" : "/";
            String separator = javaHome.endsWith("\\") || javaHome.endsWith("/")
                    ? "" : slash;
            return javaHome + separator + "bin" + slash + "java.exe";
        }
        return "java";
    }

    private String javaExecutable() {
        return resolveJavaExecutable(
                System.getProperty("monstermazeai.java17"),
                System.getenv("MONSTERMAZE_AI_JAVA"),
                System.getenv("JAVA_HOME_17_X64"));
    }

    private void closeProcess() {
        if (output != null) try { output.close(); } catch (IOException ignored) {}
        if (input != null) try { input.close(); } catch (IOException ignored) {}
        if (process != null) {
            process.destroy();
            try {
                if (process.isAlive()) process.destroyForcibly();
            } catch (UnsupportedOperationException ignored) {}
        }
        output = null;
        input = null;
        process = null;
        lastAction = LegacyAction.IDLE;
    }

    private void logUnavailableOnce(String message) {
        if (unavailableLogged) return;
        unavailableLogged = true;
        System.err.println("[MonsterMazeAI/1.8] RUNTIME UNAVAILABLE: " + message);
        System.err.println("[MonsterMazeAI/1.8] RUNTIME STATUS: " + runtimeStatus());
    }

    private static final class DecisionResult {
        final long tick;
        final LegacyAction action;
        DecisionResult(long tick, LegacyAction action) {
            this.tick = tick;
            this.action = action;
        }
    }

    private static boolean isSafeStaleTurn(LegacyAction action) {
        return action.forward == 0.0
                && action.strafe == 0.0
                && !action.jump
                && !action.sprint
                && !action.useAbility
                && Math.abs(action.yawDelta) <= 12.0F;
    }

    private static String describe(LegacyAction action) {
        return "f=" + action.forward + ",s=" + action.strafe
                + ",jump=" + action.jump + ",sprint=" + action.sprint
                + ",yawDelta=" + action.yawDelta + ",ability=" + action.useAbility;
    }
}
