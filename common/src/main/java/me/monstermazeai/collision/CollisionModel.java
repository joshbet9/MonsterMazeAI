package me.monstermazeai.collision;

import me.monstermazeai.game.GameState;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.player.PlayerState;

public final class CollisionModel {
    private static final double HIT_DISTANCE_SQ = 1.0;
    private static final double HIT_DAMAGE = 4.0;
    private static final long HIT_COOLDOWN = 20;

    public boolean tryMonsterHit(GameState state, MonsterState monster) {
        PlayerState p = state.player;
        if (!state.alive || !pHitEligible(p, state.tick)) return false;
        if (horizontalDistanceSq(p, monster) >= HIT_DISTANCE_SQ) return false;
        double dx = p.x - monster.x, dz = p.z - monster.z;
        double d = Math.sqrt(dx*dx + dz*dz);
        if (d <= 1e-9) return false;
        p.health -= HIT_DAMAGE;
        p.vx = dx / d;
        p.vz = dz / d;
        p.vy = Math.min(p.vy + 0.75, 1.2);
        if (p.grounded) p.vy += 0.2;
        p.recentMobHitUntilTick = state.tick + HIT_COOLDOWN;
        if (p.health <= 0) state.alive = false;
        return true;
    }

    private boolean pHitEligible(PlayerState p, long tick) {
        return tick >= p.recentMobHitUntilTick;
    }

    private double horizontalDistanceSq(PlayerState p, MonsterState m) {
        double dx=p.x-m.x, dz=p.z-m.z;
        return dx*dx+dz*dz;
    }
}