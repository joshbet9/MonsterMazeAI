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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Java-8 Minecraft-side process bridge with explicit sidecar I/O tracing.
 */
public final class Minecraft18AiRuntime {
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
    private static final long MAX_ACTION_AGE_TICKS = 40L;

    public boolean configured() { return runtimeJar() != null; }

    public synchronized void startIfConfigured() {
        if (!configured() || process != null) return;

        String jar = runtimeJar();
        String java = javaExecutable();
        if (jar == null || java == null) return;

        try {
            List<String> command = new ArrayList<String>();
            command.add(java);
            command.add("-jar");
            command.add(jar);

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            process = builder.start();
            input = new DataInputStream(new BufferedInputStream(process.getInputStream()));
            output = new DataOutputStream(new BufferedOutputStream(process.getOutputStream()));
            lastAction = LegacyAction.IDLE;
            decideCount = 0;

            System.out.println("[MonsterMazeAI/1.8] RUNTIME start java=" + java + " jar=" + jar);
        } catch (IOException failure) {
            closeProcess();
            System.err.println("[MonsterMazeAI/1.8] RUNTIME start failed: "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }


    /**
     * Publish the newest observation without queueing obsolete world states.
     * A planner worker consumes the latest snapshot and, when it finishes, keeps
     * processing any newer snapshot that arrived while it was computing.
     */
    public synchronized void submit(LegacyWorldObservation observation) {
        if (observation == null || process == null || output == null || input == null) return;
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
                System.err.println("[MonsterMazeAI/1.8] RUNTIME async decision failed: "
                        + failure.getClass().getSimpleName() + ": " + failure.getMessage());
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
            System.err.println("[MonsterMazeAI/1.8] RUNTIME decide(null) -> IDLE");
            return LegacyAction.IDLE;
        }

        if (process == null) startIfConfigured();
        if (process == null || output == null || input == null) {
            System.err.println("[MonsterMazeAI/1.8] RUNTIME unavailable -> IDLE");
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
            System.err.println("[MonsterMazeAI/1.8] RUNTIME sidecar exited; returning IDLE");
            closeProcess();
            return LegacyAction.IDLE;
        } catch (IOException failure) {
            System.err.println("[MonsterMazeAI/1.8] RUNTIME I/O failed: "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            closeProcess();
            return LegacyAction.IDLE;
        }
    }

    public synchronized void stop() {
        /*
         * Close the process streams first. A worker may currently be blocked in
         * readAction(); closing the streams releases that blocking I/O without
         * making the Minecraft client wait for the worker's decide() monitor.
         */
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

    static String resolveRuntimeJar(String property, String environment) {
        if (property != null && !property.trim().isEmpty()) return property.trim();
        return environment == null || environment.trim().isEmpty() ? null : environment.trim();
    }

    private String runtimeJar() {
        return resolveRuntimeJar(
                System.getProperty("monstermazeai.runtime.jar"),
                System.getenv("MONSTERMAZE_AI_RUNTIME_JAR"));
    }

    static String resolveJavaExecutable(String property, String environment, String javaHome) {
        if (property != null && !property.trim().isEmpty()) return property.trim();
        if (environment != null && !environment.trim().isEmpty()) return environment.trim();
        if (javaHome != null && !javaHome.trim().isEmpty()) {
            return javaHome + (javaHome.endsWith("\\") ? "bin\\java.exe" : "\\bin\\java.exe");
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
        if (process != null) process.destroy();
        output = null;
        input = null;
        process = null;
        lastAction = LegacyAction.IDLE;
    }

    private static final class DecisionResult {
        final long tick;
        final LegacyAction action;
        DecisionResult(long tick, LegacyAction action) { this.tick = tick; this.action = action; }
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
