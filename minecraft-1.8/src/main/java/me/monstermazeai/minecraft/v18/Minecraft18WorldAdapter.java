package me.monstermazeai.minecraft.v18;

import me.monstermazeai.adapter.LegacyWorldObservation;

/**
 * Minecraft 1.8 client bridge.
 *
 * This implementation is deliberately read-only. It exposes the normalized
 * observation without depending on the modern planner/simulator classes,
 * keeping the legacy client compatible with Java 8.
 */
public final class Minecraft18WorldAdapter {
    private final Minecraft18Observer observer;

    public Minecraft18WorldAdapter() {
        this.observer = new Minecraft18Observer();
    }

    public Minecraft18Observer observer() {
        return observer;
    }

    public LegacyWorldObservation observe() {
        return observer.observe().state.copy();
    }
}
