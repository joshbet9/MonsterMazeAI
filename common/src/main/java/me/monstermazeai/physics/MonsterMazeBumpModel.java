package me.monstermazeai.physics;

import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.player.PlayerState;

import java.util.List;

/** Exact collision semantics of MonsterManager.bump(), plus QOL kit rules. */
public final class MonsterMazeBumpModel {
    public static final double CONTACT_DISTANCE = 1.0;
    public static final double DAMAGE = 4.0;
    public static final long RECHARGE_TICKS = 20L;

    private MonsterMazeBumpModel() {}

    /** Legacy overload retained for focused physics tests. */
    public static int apply(PlayerState player, List<MonsterState> monsters, long tick) {
        if (player.recentMobHitUntilTick > tick || player.health <= 0.0) return 0;

        // MonsterManager.bump() stops after the first valid collision.
        for (MonsterState monster : monsters) {
            if (monster.removed) continue;
            if (!contact(player, monster)) continue;
            applyNormalBump(player, monster, tick);
            return 1;
        }
        return 0;
    }

    /** Full game-aware bump including Body Builder and Maverick QOL behaviour. */
    public static int apply(GameState game) {
        for (MonsterState monster : game.monsters) {
            if (apply(game, monster) == 1) return 1;
        }
        return 0;
    }

    /**
     * Apply source bump semantics to one authoritative monster.
     *
     * This overload is used by the compatibility facade and avoids mutating
     * the state's monster collection just to test a supplied collision target.
     */
    public static int apply(GameState game, MonsterState monster) {
        PlayerState player = game.player;
        boolean bodyRush = game.kit == Kit.BODY_BUILDER
                && game.mode != me.monstermazeai.game.Mode.ORIGINAL
                && game.ability.activeUntilTick > game.tick;

        if ((!bodyRush && player.recentMobHitUntilTick > game.tick) || player.health <= 0.0) return 0;
        if (isOnAnyPad(game)) return 0;
        if (monster == null || monster.removed || monster.launched(game.tick)) return 0;
        if (!contact(player, monster)) return 0;

        if (bodyRush) {
            // Source Body Rush: player is immune to monster damage/knockback;
            // the monster is launched and the active duration loses 2 seconds.
            launchMonsterAwayFromPlayer(monster, player, game.tick);
            game.ability.activeUntilTick = Math.max(game.tick, game.ability.activeUntilTick - 40L);
            player.mobHitGraceUntilTick = game.tick + 40L;
            return 1;
        }

        applyNormalBump(game, monster);
        return 1;
    }

    private static void applyNormalBump(GameState game, MonsterState monster) {
        PlayerState player = game.player;
        double dx = player.x - monster.x;
        double dy = player.y - monster.y;
        double dz = player.z - monster.z;
        // MonsterManager.bump() snaps a player just above the floor before
        // applying the velocity when the contact occurs near floor level.
        boolean groundedForBoost = player.grounded;
        if (player.y >= GameState.PATH_Y && player.y < GameState.PATH_Y + 0.9) {
            player.y = GameState.PATH_Y + 0.7;
        }

        double horizontal = Math.hypot(dx, dz);
        double vx;
        double vz;
        if (horizontal > 1.0E-12) {
            vx = dx / horizontal;
            vz = dz / horizontal;
        } else {
            // Source fallback: when the horizontal trajectory is zero, use
            // the opposite of the player's facing direction.
            double yaw = Math.toRadians(player.yaw);
            vx = Math.sin(yaw);
            vz = -Math.cos(yaw);
        }
        double vy = 0.0;

        if (game.kit == Kit.MAVERICK && game.mode != me.monstermazeai.game.Mode.ORIGINAL) {
            // Source getMobKnockTarget(): active SafePad, else preview SafePad.
            int row = game.activePadRow >= 0 ? game.activePadRow : game.previewPadRow;
            int col = game.activePadColumn >= 0 ? game.activePadColumn : game.previewPadColumn;
            double tx = row + 0.5 - player.x;
            double tz = col + 0.5 - player.z;
            double len = Math.hypot(tx, tz);
            if (row >= 0 && col >= 0 && len > 1.0E-9) {
                vx = tx / len;
                vz = tz / len;
                vy = 0.0;
            }
        }

        vy += 0.75;
        if (vy > 1.2) vy = 1.2;
        if (groundedForBoost) vy += 0.2;

        player.vx = vx;
        player.vy = vy;
        player.vz = vz;
        player.pendingAirborne = true;
        player.grounded = false;
        player.health -= DAMAGE;
        player.damageTaken += DAMAGE;
        player.recentMobHitUntilTick = game.tick + RECHARGE_TICKS;
        player.mobHitGraceUntilTick = game.tick + 40L;
    }

    private static void applyNormalBump(PlayerState player, MonsterState monster, long tick) {
        double dx = player.x - monster.x;
        double dy = player.y - monster.y;
        double dz = player.z - monster.z;
        double horizontal = Math.hypot(dx, dz);
        double vx;
        double vz;
        if (horizontal > 1.0E-12) {
            vx = dx / horizontal;
            vz = dz / horizontal;
        } else {
            double yaw = Math.toRadians(player.yaw);
            vx = Math.sin(yaw);
            vz = -Math.cos(yaw);
        }
        double vy = 0.0;
        vy += 0.75;
        if (vy > 1.2) vy = 1.2;
        if (player.grounded) vy += 0.2;

        player.vx = vx;
        player.vy = vy;
        player.vz = vz;
        player.pendingAirborne = true;
        player.health -= DAMAGE;
        player.damageTaken += DAMAGE;
        player.recentMobHitUntilTick = tick + RECHARGE_TICKS;
        player.mobHitGraceUntilTick = tick + 40L;
    }

    private static void launchMonsterAwayFromPlayer(MonsterState monster, PlayerState player, long tick) {
        double dx = monster.x - player.x;
        double dz = monster.z - player.z;
        double len = Math.hypot(dx, dz);
        if (len < 1.0E-9) { dx = 1.0; dz = 0.0; len = 1.0; }
        monster.vx = dx / len;
        monster.vz = dz / len;
        monster.vy = 0.95;
        monster.launchedAtTick = tick;
        monster.launchedUntilTick = tick + 30;
        monster.waypointRow = -1;
        monster.waypointColumn = -1;
    }

    private static boolean isOnAnyPad(GameState game) {
        return game.oldPadContains(game.player)
                || (game.activePadRow >= 0 && game.activePadColumn >= 0
                && me.monstermazeai.game.PadModel.isOn(game.player, game.activePadRow + 0.5,
                GameState.PAD_SURFACE_Y, game.activePadColumn + 0.5))
                || (game.previewPadRow >= 0 && game.previewPadColumn >= 0
                && me.monstermazeai.game.PadModel.isOn(game.player, game.previewPadRow + 0.5,
                GameState.PAD_SURFACE_Y, game.previewPadColumn + 0.5));
    }

    private static boolean contact(PlayerState player, MonsterState monster) {
        double dx = player.x - monster.x;
        double dy = player.y - monster.y;
        double dz = player.z - monster.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) < CONTACT_DISTANCE;
    }
}
