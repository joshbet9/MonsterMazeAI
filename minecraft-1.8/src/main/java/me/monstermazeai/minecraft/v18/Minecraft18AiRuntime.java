package me.monstermazeai.minecraft.v18;

import me.monstermazeai.adapter.LegacyAction;
import me.monstermazeai.adapter.LegacyProtocol;
import me.monstermazeai.adapter.LegacyWorldObservation;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Java-8 Minecraft-side process bridge to the Java-17 AI runtime.
 *
 * The bridge is opt-in. Set MONSTERMAZE_AI_RUNTIME_JAR to the built shaded
 * runtime JAR. MONSTERMAZE_AI_JAVA may point at a Java 17 executable; otherwise
 * JAVA_HOME_17_X64 is used, then java on PATH.
 */
public final class Minecraft18AiRuntime {
    private Process process;
    private DataOutputStream input;
    private DataInputStream output;
    private boolean failed;

    public boolean configured() {
        return runtimeJar() != null && !runtimeJar().isEmpty();
    }

    public boolean running() {
        return process != null && process.isAlive() && !failed;
    }

    public void startIfConfigured() {
        if (!configured() || running() || failed) return;
        try {
            String java = javaExecutable();
            String jar = runtimeJar();
            ProcessBuilder builder = new ProcessBuilder(java, "-jar", jar);
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            process = builder.start();
            input = new DataOutputStream(new BufferedOutputStream(process.getOutputStream()));
            output = new DataInputStream(new BufferedInputStream(process.getInputStream()));
        } catch (Exception ex) {
            failed = true;
            System.err.println("[MonsterMazeAI/1.8] failed to start AI runtime: " + ex);
            stop();
        }
    }

    public LegacyAction decide(LegacyWorldObservation observation) {
        if (observation == null) return LegacyAction.IDLE;
        startIfConfigured();
        if (!running()) return LegacyAction.IDLE;

        try {
            LegacyProtocol.writeObservation(input, observation);
            input.flush();
            return LegacyProtocol.readAction(output);
        } catch (IOException ex) {
            failed = true;
            System.err.println("[MonsterMazeAI/1.8] AI runtime disconnected: " + ex);
            stop();
            return LegacyAction.IDLE;
        }
    }

    public void stop() {
        try {
            if (input != null) input.close();
        } catch (IOException ignored) {
        }
        try {
            if (output != null) output.close();
        } catch (IOException ignored) {
        }
        if (process != null) {
            process.destroy();
            process = null;
        }
        input = null;
        output = null;
    }

    private static String runtimeJar() {
        String property = System.getProperty("monstermazeai.runtime.jar");
        if (property != null && !property.trim().isEmpty()) return property.trim();
        String env = System.getenv("MONSTERMAZE_AI_RUNTIME_JAR");
        return env == null || env.trim().isEmpty() ? null : env.trim();
    }

    private static String javaExecutable() {
        String configured = System.getenv("MONSTERMAZE_AI_JAVA");
        if (configured != null && !configured.trim().isEmpty()) return configured.trim();

        String javaHome = System.getenv("JAVA_HOME_17_X64");
        if (javaHome != null && !javaHome.trim().isEmpty()) {
            File executable = new File(javaHome, isWindows() ? "bin\\java.exe" : "bin/java");
            if (executable.isFile()) return executable.getAbsolutePath();
        }

        return "java";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
