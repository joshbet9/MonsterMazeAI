package me.monstermazeai.physics;

import me.monstermazeai.player.Action;
import me.monstermazeai.player.PlayerState;

public interface PhysicsModel {
    void tick(PlayerState player, Action action);
}