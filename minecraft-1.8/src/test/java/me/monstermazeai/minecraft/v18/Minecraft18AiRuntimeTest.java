package me.monstermazeai.minecraft.v18;

import org.junit.Test;

import static org.junit.Assert.*;

public class Minecraft18AiRuntimeTest {
    @Test
    public void runtimeIsOptInWhenNoJarIsConfigured() {
        String previous = System.getProperty("monstermazeai.runtime.jar");
        try {
            System.clearProperty("monstermazeai.runtime.jar");
            Minecraft18AiRuntime runtime = new Minecraft18AiRuntime();
            assertFalse(runtime.configured());
        } finally {
            if (previous != null) System.setProperty("monstermazeai.runtime.jar", previous);
        }
    }
}
