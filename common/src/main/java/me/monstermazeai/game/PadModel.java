package me.monstermazeai.game;

import me.monstermazeai.player.PlayerState;

public final class PadModel {
    public boolean isOn(PlayerState p, double padX, double padY, double padZ) {
        double dx=p.x-padX, dz=p.z-padZ;
        return dx > -2.5 && dx < 2.5 && dz > -2.5 && dz < 2.5
                && p.y > padY && p.y < padY + 5.0;
    }
}