package me.monstermazeai.game;

import me.monstermazeai.player.PlayerState;

public final class PadModel {
    private PadModel() {}

    /**
     * Source-equivalent SafePad.isOn check.
     *
     * The live 1.8 game constructs a pad from a path Location and sets its
     * surface to pathLocation.blockY - 1. The common simulator normalizes the
     * path/player feet Y to GameState.PATH_Y, therefore callers should pass
     * GameState.PAD_SURFACE_Y (-1) rather than the player ground Y (0).
     */
    public static boolean isOn(PlayerState p, double padX, double padY, double padZ) {
        if (Double.isNaN(padX) || Double.isNaN(padZ)) return false;

        double dx = p.x - padX;
        double dz = p.z - padZ;

        return dx > -2.5 && dx < 2.5
                && dz > -2.5 && dz < 2.5
                && p.y > padY
                && p.y < padY + 5.0;
    }
}
