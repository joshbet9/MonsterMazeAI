package me.monstermazeai.minecraft.v18;

import org.junit.Test;

import static org.junit.Assert.*;

public class Minecraft18AiRuntimeTest {
    @Test
    public void runtimeConfigurationIsOptInWhenNoJarIsConfigured() {
        assertNull(Minecraft18AiRuntime.resolveRuntimeJar(null, null));
        assertNull(Minecraft18AiRuntime.resolveRuntimeJar("  ", "\t"));
    }

    @Test
    public void runtimeJarPropertyOverridesEnvironment() {
        assertEquals("property.jar",
                Minecraft18AiRuntime.resolveRuntimeJar("  property.jar  ", "environment.jar"));
    }

    @Test
    public void runtimeJarFallsBackToEnvironment() {
        assertEquals("environment.jar",
                Minecraft18AiRuntime.resolveRuntimeJar(null, "  environment.jar  "));
    }

    @Test
    public void javaPropertyOverridesEnvironmentAndJavaHome() {
        assertEquals("C:\\custom\\java.exe",
                Minecraft18AiRuntime.resolveJavaExecutable(
                        "  C:\\custom\\java.exe  ",
                        "C:\\environment\\java.exe",
                        "C:\\java-home"));
    }

    @Test
    public void javaEnvironmentOverridesJavaHome() {
        assertEquals("C:\\environment\\java.exe",
                Minecraft18AiRuntime.resolveJavaExecutable(
                        null,
                        "  C:\\environment\\java.exe  ",
                        "C:\\java-home"));
    }

    @Test
    public void javaHomeBuildsJavaExecutable() {
        assertEquals("C:\\java-home\\bin\\java.exe",
                Minecraft18AiRuntime.resolveJavaExecutable(null, null, "C:\\java-home"));
        assertEquals("C:\\java-home\\bin\\java.exe",
                Minecraft18AiRuntime.resolveJavaExecutable(null, null, "C:\\java-home\\"));
    }

    @Test
    public void javaFallsBackToPathLookup() {
        assertEquals("java", Minecraft18AiRuntime.resolveJavaExecutable(null, null, null));
    }
}
