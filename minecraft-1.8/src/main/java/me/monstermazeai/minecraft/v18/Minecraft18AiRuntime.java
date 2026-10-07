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
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /**
     * Async sidecar results normally arrive 1 tick after their observation. Allow
     * a small bounded latency window in live Minecraft; genuinely old commands
     * still fail closed rather than being applied indefinitely.
     */
    private static final long MAX_ACTION_AGE_TICKS = 1L;

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

    /*
     * Process creation and runtime/JDK discovery can take seconds under LabyMod.
     * Keep that work completely off Minecraft's client thread.
     */
    private final ExecutorService startupExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "MonsterMazeAI-1.8-runtime-start");
        thread.setDaemon(true);
        return thread;
    });

    private Future<?> pendingStartup;
    private volatile boolean stopping;
    private Future<?> pendingDecision;
    private LegacyWorldObservation latestObservation;
    private DecisionResult latestCompletedDecision;
    private long lastSubmittedTick = Long.MIN_VALUE;
    private long lastCompletedTick = Long.MIN_VALUE;
    private long completedSequence;
    private long lastAppliedDecisionSequence;
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
    /**
     * Request sidecar startup without doing any process creation or runtime
     * discovery on the Minecraft client thread.
     */
    public synchronized void startIfConfigured() {
        stopping = false;
        if (process != null && process.isAlive()) return;
        if (pendingStartup != null && !pendingStartup.isDone()) return;
        pendingStartup = startupExecutor.submit(this::startProcessWorker);
    }

    private void startProcessWorker() {
        String jar;
        String java;
        synchronized (this) {
            if (stopping) return;
        }

        try {
            jar = runtimeJar();
            java = javaExecutable();

            synchronized (this) {
                if (!configurationLogged) {
                    configurationLogged = true;
                    System.out.println("[MonsterMazeAI/1.8] RUNTIME CONFIG " + runtimeStatus());
                }
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

            List<String> command = new ArrayList<String>();
            command.add(java);
            command.add("-jar");
            command.add(jarPath.toAbsolutePath().toString());

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            Process child = builder.start();

            synchronized (this) {
                if (stopping) {
                    child.destroy();
                    return;
                }
                process = child;
                input = new DataInputStream(new BufferedInputStream(child.getInputStream()));
                output = new DataOutputStream(new BufferedOutputStream(child.getOutputStream()));
                lastAction = LegacyAction.IDLE;
                decideCount = 0;
                unavailableLogged = false;

                System.out.println("[MonsterMazeAI/1.8] RUNTIME STARTED java=" + java
                        + " jar=" + jarPath.toAbsolutePath());
            }
        } catch (IOException failure) {
            if (!stopping) {
                closeProcess();
                logUnavailableOnce("Sidecar start failed: "
                        + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
        } catch (RuntimeException failure) {
            if (!stopping) {
                closeProcess();
                logUnavailableOnce("Sidecar start failed: "
                        + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
        }
    }

    /**
     * Publish the newest observation without queueing obsolete world states.
     * A planner worker consumes the latest snapshot and, when it finishes, keeps
     * processing any newer snapshot that arrived while it was computing.
     */
    public synchronized void submit(LegacyWorldObservation observation) {
        if (observation == null) return;

        latestObservation = observation;
        if (process == null || !process.isAlive() || output == null || input == null) {
            startIfConfigured();
        }
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
        if (result == null || result.action == null) return null;

        long age = currentTick - result.tick;

        /*
         * Deterministic live cadence: observe N -> decide N -> apply on N+1.
         * A result from the current tick is therefore still queued, even if the
         * sidecar answered immediately. This prevents runtime speed from changing
         * the control law relative to the simulator.
         */
        if (age < 1L) {
            return null;
        }

        if (result.sequence <= lastAppliedDecisionSequence) {
            latestCompletedDecision = null;
            return null;
        }

        if (age > MAX_ACTION_AGE_TICKS) {
            latestCompletedDecision = null;
            lastAppliedDecisionSequence = result.sequence;
            lastCompletedTick = result.tick;
            return LegacyAction.IDLE;
        }

        latestCompletedDecision = null;
        lastAppliedDecisionSequence = result.sequence;
        lastCompletedTick = result.tick;

        /*
         * The simulator consumes the exact queued Action on N+1. Preserve all
         * fields here: WASD, jump, sprint, yaw pulse, and ability pulse.
         */
        return result.action;
    }


    /** Worker loop that always consumes the newest available observation. */
    private void processLatestObservations() {
        if (!awaitProcessReady()) {
            synchronized (this) {
                latestObservation = null;
                pendingDecision = null;
            }
            return;
        }

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
                latestCompletedDecision = new DecisionResult(submitted.worldTick, action, ++completedSequence);
                if (latestObservation == null) {
                    pendingDecision = null;
                    return;
                }
            }
        }
    }


    public synchronized boolean decisionPending() {
        return latestObservation != null || (pendingDecision != null && !pendingDecision.isDone());
    }

    public LegacyAction decide(LegacyWorldObservation observation) {
        if (observation == null) {
            System.err.println("[MonsterMazeAI/1.8] RUNTIME DECIDE(null) -> IDLE");
            return LegacyAction.IDLE;
        }

        if (!awaitProcessReady()) {
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

            long wireStart = System.nanoTime();
            LegacyProtocol.writeObservation(output, observation);
            output.flush();

            LegacyAction action = LegacyProtocol.readAction(input);
            long wireElapsedMicros = (System.nanoTime() - wireStart) / 1000L;
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
        stopping = true;
        if (pendingStartup != null) pendingStartup.cancel(true);
        closeProcess();
        if (pendingDecision != null) pendingDecision.cancel(true);
        latestObservation = null;
        latestCompletedDecision = null;
        pendingDecision = null;
        lastSubmittedTick = Long.MIN_VALUE;
        lastCompletedTick = Long.MIN_VALUE;
        completedSequence = 0L;
        lastAppliedDecisionSequence = 0L;
        lastCompletedWasStaleTurn = false;
    }

    private boolean awaitProcessReady() {
        while (true) {
            Future<?> startup;
            synchronized (this) {
                if (stopping) return false;
                if (process != null && process.isAlive() && output != null && input != null) return true;
                startIfConfigured();
                startup = pendingStartup;
            }

            if (startup == null) return false;
            try {
                startup.get(15, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException timeout) {
                startup.cancel(true);
                logUnavailableOnce("Sidecar startup timed out");
                return false;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            } catch (java.util.concurrent.CancellationException cancelled) {
                return false;
            } catch (java.util.concurrent.ExecutionException failure) {
                logUnavailableOnce("Sidecar startup failed: "
                        + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                return false;
            }
        }
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
        String explicit = resolveJavaExecutable(
                System.getProperty("monstermazeai.java17"),
                System.getenv("MONSTERMAZE_AI_JAVA"),
                System.getenv("JAVA_HOME_17_X64"));
        if (!"java".equals(explicit)) return explicit;

        Path discovered = discoverJava17();
        return discovered == null ? explicit : discovered.toAbsolutePath().toString();
    }

    private Path discoverJava17() {
        String home = System.getProperty("user.home");
        String[] roots = new String[] {
                System.getenv("JAVA_HOME"),
                "C:\\Program Files\\Eclipse Adoptium",
                "C:\\Program Files\\Java",
                home == null ? null : home + "\\AppData\\Local\\Programs\\Eclipse Adoptium"
        };

        for (String rootValue : roots) {
            if (rootValue == null || rootValue.trim().isEmpty()) continue;
            Path root = Paths.get(rootValue);
            Path direct = root.resolve("bin\\java.exe");
            if (Files.isRegularFile(direct) && isJava17OrNewer(direct)) return direct;
            if (!Files.isDirectory(root)) continue;
            try {
                java.util.List<Path> matches = new ArrayList<Path>();
                java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(root);
                try {
                    for (Path child : stream) {
                        Path java = child.resolve("bin\\java.exe");
                        if (Files.isRegularFile(java)) matches.add(java);
                    }
                } finally {
                    stream.close();
                }
                for (Path java : matches) {
                    if (isJava17OrNewer(java)) return java;
                }
            } catch (IOException ignored) {
                // Try the next conventional installation root.
            }
        }
        return null;
    }

    private boolean isJava17OrNewer(Path java) {
        Process probe = null;
        try {
            probe = new ProcessBuilder(java.toString(), "-version")
                    .redirectErrorStream(true)
                    .start();
            if (!probe.waitFor(2, TimeUnit.SECONDS)) {
                probe.destroy();
                return false;
            }
            String output = new String(readAll(probe.getInputStream()), "UTF-8");
            Matcher matcher =
                    Pattern.compile("(?:version|openjdk)\\s+\\\"?(\\d+)")
                            .matcher(output);
            return matcher.find() && Integer.parseInt(matcher.group(1)) >= 17;
        } catch (Exception ignored) {
            if (probe != null) probe.destroy();
            return false;
        }
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[512];
        int count;
        while ((count = stream.read(buffer)) >= 0) bytes.write(buffer, 0, count);
        return bytes.toByteArray();
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
        final long sequence;
        DecisionResult(long tick, LegacyAction action, long sequence) {
            this.tick = tick;
            this.action = action;
            this.sequence = sequence;
        }
    }


    private static String describe(LegacyAction action) {
        return "f=" + action.forward + ",s=" + action.strafe
                + ",jump=" + action.jump + ",sprint=" + action.sprint
                + ",yawDelta=" + action.yawDelta + ",ability=" + action.useAbility;
    }
}
