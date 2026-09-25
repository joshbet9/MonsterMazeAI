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

/**
 * Java-8 Minecraft-side process bridge with explicit sidecar I/O tracing.
 */
public final class Minecraft18AiRuntime {
    private Process process;
    private DataInputStream input;
    private DataOutputStream output;
    private LegacyAction lastAction = LegacyAction.IDLE;
    private long decideCount;

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

    public synchronized LegacyAction decide(LegacyWorldObservation observation) {
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

    public synchronized void stop() { closeProcess(); }
    public synchronized LegacyAction lastAction() { return lastAction; }

    private String runtimeJar() {
        String property = System.getProperty("monstermazeai.runtime.jar");
        if (property != null && !property.trim().isEmpty()) return property.trim();
        String environment = System.getenv("MONSTERMAZE_AI_RUNTIME_JAR");
        return environment == null || environment.trim().isEmpty() ? null : environment.trim();
    }

    private String javaExecutable() {
        String property = System.getProperty("monstermazeai.java17");
        if (property != null && !property.trim().isEmpty()) return property.trim();
        String environment = System.getenv("MONSTERMAZE_AI_JAVA");
        if (environment != null && !environment.trim().isEmpty()) return environment.trim();
        String javaHome = System.getenv("JAVA_HOME_17_X64");
        if (javaHome != null && !javaHome.trim().isEmpty()) {
            return javaHome + (javaHome.endsWith("\\") ? "bin\\java.exe" : "\\bin\\java.exe");
        }
        return "java";
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

    private static String describe(LegacyAction action) {
        return "f=" + action.forward + ",s=" + action.strafe
                + ",jump=" + action.jump + ",sprint=" + action.sprint
                + ",yawDelta=" + action.yawDelta + ",ability=" + action.useAbility;
    }
}
