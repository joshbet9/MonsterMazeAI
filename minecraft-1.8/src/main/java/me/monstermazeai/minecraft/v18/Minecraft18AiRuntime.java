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
 * Java-8 Minecraft-side process bridge for the Java-17 AI sidecar.
 *
 * The client sends one normalized observation and receives one action for each
 * client tick. The sidecar is deliberately a separate JVM so the planner can
 * remain Java 17 without making the Forge 1.8 client depend on Java 17 APIs.
 */
public final class Minecraft18AiRuntime {
    private Process process;
    private DataInputStream input;
    private DataOutputStream output;
    private LegacyAction lastAction = LegacyAction.IDLE;

    public boolean configured() {
        return runtimeJar() != null;
    }

    public synchronized void startIfConfigured() {
        if (!configured() || process != null) {
            return;
        }

        String jar = runtimeJar();
        String java = javaExecutable();
        if (jar == null || java == null) {
            return;
        }

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

            System.out.println("[MonsterMazeAI/1.8] started AI sidecar: " + jar);
        } catch (IOException failure) {
            closeProcess();
            System.err.println("[MonsterMazeAI/1.8] failed to start AI sidecar: "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    public synchronized LegacyAction decide(LegacyWorldObservation observation) {
        if (observation == null) {
            return LegacyAction.IDLE;
        }

        if (process == null) {
            startIfConfigured();
        }
        if (process == null || output == null || input == null) {
            return LegacyAction.IDLE;
        }

        try {
            LegacyProtocol.writeObservation(output, observation);
            output.flush();

            LegacyAction action = LegacyProtocol.readAction(input);
            lastAction = action == null ? LegacyAction.IDLE : action;
            return lastAction;
        } catch (EOFException end) {
            System.err.println("[MonsterMazeAI/1.8] AI sidecar exited; returning IDLE");
            closeProcess();
            return LegacyAction.IDLE;
        } catch (IOException failure) {
            System.err.println("[MonsterMazeAI/1.8] AI sidecar I/O failed: "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            closeProcess();
            return LegacyAction.IDLE;
        }
    }

    public synchronized void stop() {
        closeProcess();
    }

    public synchronized LegacyAction lastAction() {
        return lastAction;
    }

    private String runtimeJar() {
        String property = System.getProperty("monstermazeai.runtime.jar");
        if (property != null && !property.trim().isEmpty()) {
            return property.trim();
        }
        String environment = System.getenv("MONSTERMAZE_AI_RUNTIME_JAR");
        return environment == null || environment.trim().isEmpty()
                ? null : environment.trim();
    }

    private String javaExecutable() {
        String property = System.getProperty("monstermazeai.java17");
        if (property != null && !property.trim().isEmpty()) {
            return property.trim();
        }

        String environment = System.getenv("MONSTERMAZE_AI_JAVA");
        if (environment != null && !environment.trim().isEmpty()) {
            return environment.trim();
        }

        String javaHome = System.getenv("JAVA_HOME_17_X64");
        if (javaHome != null && !javaHome.trim().isEmpty()) {
            return javaHome + (javaHome.endsWith("\\") ? "bin\\java.exe" : "\\bin\\java.exe");
        }

        return "java";
    }

    private void closeProcess() {
        if (output != null) {
            try {
                output.close();
            } catch (IOException ignored) {
            }
        }
        if (input != null) {
            try {
                input.close();
            } catch (IOException ignored) {
            }
        }
        if (process != null) {
            process.destroy();
        }
        output = null;
        input = null;
        process = null;
        lastAction = LegacyAction.IDLE;
    }
}
