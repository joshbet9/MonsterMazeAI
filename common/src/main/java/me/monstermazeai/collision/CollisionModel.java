package me.monstermazeai.collision;

import me.monstermazeai.ability.AbilityModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.game.Mode;
import me.monstermazeai.game.PadModel;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.monster.MonsterState;

public final class CollisionModel {
    private static final double HIT_DISTANCE_SQ = 1.0;
    private static final double HIT_DAMAGE = 4.0;
    private static final long HIT_COOLDOWN_TICKS = 20;
    private static final double KNOCKBACK_HORIZONTAL = 1.0;
    private static final double KNOCKBACK_VERTICAL = 0.75;
    private static final double GROUND_BOOST = 0.2;
    private static final double KNOCKBACK_MAX_VERTICAL = 1.2;

    public void tryMonsterHit(GameState state, MonsterState monster) {
        tryMonsterHit(state, monster, new AbilityModel());
    }

    public void tryMonsterHit(GameState state, MonsterState monster, AbilityModel abilities) {
        if (!state.alive || monster.launched(state.tick)
                || (state.activePadRow >= 0 && state.activePadColumn >= 0
                && PadModel.isOn(state.player, state.activePadRow + 0.5,
                GameState.PAD_SURFACE_Y, state.activePadColumn + 0.5))) {
            return;
        }

        double dx = state.player.x - monster.x;
        double dz = state.player.z - monster.z;
        if (dx*dx + dz*dz >= HIT_DISTANCE_SQ) return;

        double dy = state.player.y - monster.y;
        if (dx*dx + dy*dy + dz*dz >= HIT_DISTANCE_SQ) return;

        if (abilities.isBodyRushActive(state)) {
            launchMonsterAwayFromPlayer(state, monster);
            abilities.consumeBodyRushContact(state);
            return;
        }

        long now = state.tick;
        if (state.player.recentMobHitUntilTick > now) return;

        state.player.recentMobHitUntilTick = now + HIT_COOLDOWN_TICKS;
        state.player.health -= HIT_DAMAGE;
        state.player.damageTaken += HIT_DAMAGE;

        // Source bump(): snap a player close to the floor upward before applying
        // knockback. UtilAction.velocity then adds +0.2 when grounded.
        boolean groundedForBoost = state.player.grounded;
        if (state.player.y >= 0.0 && state.player.y < 0.9) {
            state.player.y = 0.7;
        }

        double knockX;
        double knockZ;
        if (state.kit == Kit.MAVERICK && state.mode != Mode.ORIGINAL) {
            int row = state.activePadRow >= 0 ? state.activePadRow : state.previewPadRow;
            int col = state.activePadRow >= 0 ? state.activePadColumn : state.previewPadColumn;
            if (row >= 0 && col >= 0) {
                knockX = row + 0.5 - state.player.x;
                knockZ = col + 0.5 - state.player.z;
            } else {
                knockX = dx;
                knockZ = dz;
            }
        } else {
            knockX = dx;
            knockZ = dz;
        }

        double len = Math.hypot(knockX, knockZ);
        if (len < 1e-9) {
            double yaw = Math.toRadians(state.player.yaw);
            knockX = -Math.sin(yaw);
            knockZ = Math.cos(yaw);
            len = 1.0;
        }

        knockX /= len;
        knockZ /= len;
        state.player.vx = knockX * KNOCKBACK_HORIZONTAL;
        state.player.vz = knockZ * KNOCKBACK_HORIZONTAL;

        double vertical = KNOCKBACK_VERTICAL;
        if (groundedForBoost) vertical += GROUND_BOOST;
        state.player.vy = Math.min(KNOCKBACK_MAX_VERTICAL, vertical);
        state.player.grounded = false;

        if (state.player.health <= 0) state.alive = false;
    }

    private void launchMonsterAwayFromPlayer(GameState state, MonsterState monster) {
        double awayX = monster.x - state.player.x;
        double awayZ = monster.z - state.player.z;
        double len = Math.hypot(awayX, awayZ);
        if (len < 1e-9) {
            awayX = 1.0;
            awayZ = 0.0;
            len = 1.0;
        }

        monster.vx = awayX / len;
        monster.vz = awayZ / len;
        monster.vy = 1.0; // UtilAction yAdd=0.8 + grounded +0.2
        monster.launchedAtTick = state.tick;
        monster.launchedUntilTick = state.tick + 30;
        monster.waypointRow = -1;
        monster.waypointColumn = -1;
    }
}
