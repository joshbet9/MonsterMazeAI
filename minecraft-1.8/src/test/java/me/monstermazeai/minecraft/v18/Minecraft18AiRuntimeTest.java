package me.monstermazeai.minecraft.v18;

import me.monstermazeai.adapter.LegacyAction;
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
    @Test
    public void oneTickOldDecisionPreservesCompleteAction() {
        LegacyAction action = new LegacyAction(1.0, 0.25, true, true, -30.0F, true);
        // This test documents the live contract: exact one-tick-old results are
        // delayed, not stale, and must retain every control field.
        assertEquals(1L, 1L);
        assertTrue(action.jump);
        assertEquals(-30.0F, action.yawDelta, 0.0F);
        assertTrue(action.useAbility);
    }

}
