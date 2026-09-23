package me.monstermazeai.minecraft.v18;

import me.monstermazeai.adapter.WorldAdapter;
import me.monstermazeai.adapter.WorldObservation;
import me.monstermazeai.player.Action;

/**
 * Minecraft 1.8 client bridge.
 *
 * This implementation is deliberately read-only. execute() remains disabled
 * until live observation has been validated against recorded Mineplex games.
 */
public final class Minecraft18WorldAdapter implements WorldAdapter {
    private final Minecraft18Observer observer;

    public Minecraft18WorldAdapter() {
        this.observer = new Minecraft18Observer();
    }

    public Minecraft18Observer observer() {
        return observer;
    }

    @Override
    public WorldObservation observe() {
        return new WorldObservation(observer.observe().state.copy());
    }

    @Override
    public void execute(Action action) {
        throw new UnsupportedOperationException(
                "Minecraft 1.8 adapter is read-only until observation is validated");
    }
}
