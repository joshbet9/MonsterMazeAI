package me.monstermazeai.adapter;

import me.monstermazeai.game.GameState;

public record WorldObservation(GameState state) {
    public WorldObservation {
        if (state == null) throw new IllegalArgumentException("state");
    }

    public WorldObservation copy() {
        return new WorldObservation(state.copy());
    }
}
