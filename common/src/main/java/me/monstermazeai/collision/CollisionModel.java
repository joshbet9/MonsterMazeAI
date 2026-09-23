package me.monstermazeai.collision;

import me.monstermazeai.game.GameState;
import me.monstermazeai.game.PadModel;
import me.monstermazeai.monster.MonsterState;

public final class CollisionModel {
    private static final double HIT_DISTANCE_SQ = 1.0;
    private static final double HIT_DAMAGE = 4.0;
    private static final long HIT_COOLDOWN_TICKS = 20;

    public void tryMonsterHit(GameState state, MonsterState monster) {
        if (!state.alive || monster.launched() || PadModel.isOn(
                state.player, state.activePadRow, 0, state.activePadColumn)) return;

        double dx = state.player.x - monster.x;
        double dz = state.player.z - monster.z;
        if (dx*dx + dz*dz >= HIT_DISTANCE_SQ) return;

        double dy = state.player.y - monster.y;
        if (dx*dx + dy*dy + dz*dz >= HIT_DISTANCE_SQ) return;

        long now=state.tick;
        if (state.player.recentMobHitUntilTick > now) return;

        state.player.recentMobHitUntilTick = now + HIT_COOLDOWN_TICKS;
        state.player.health -= HIT_DAMAGE;

        double len=Math.hypot(dx,dz);
        if(len < 1e-9) {
            double yaw=Math.toRadians(state.player.yaw);
            dx=-Math.sin(yaw);
            dz=Math.cos(yaw);
            len=1.0;
        }
        dx/=len; dz/=len;
        state.player.vx=dx;
        state.player.vz=dz;
        state.player.vy=Math.min(1.2,0.75 + (state.player.grounded ? 0.2 : 0.0));
        state.player.grounded=false;

        if(state.player.health <= 0) state.alive=false;
    }
}
