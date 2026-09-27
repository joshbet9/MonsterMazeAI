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
        PlayerState player = game.player;
        if (player.recentMobHitUntilTick > game.tick || player.health <= 0.0) return 0;

        for (MonsterState monster : game.monsters) {
            if (monster.removed) continue;
            if (!contact(player, monster)) continue;

            if (game.kit == Kit.BODY_BUILDER && game.mode != me.monstermazeai.game.Mode.ORIGINAL
                    && game.ability.activeUntilTick > game.tick) {
                // Source Body Rush: player is immune to monster damage/knockback;
                // the monster is launched and the active duration loses 2 seconds.
                launchMonsterAwayFromPlayer(monster, player, game.tick);
                game.ability.activeUntilTick = Math.max(game.tick, game.ability.activeUntilTick - 40L);
                return 1;
            }

            applyNormalBump(game, monster);
            return 1;
        }
        return 0;
    }

    private static void applyNormalBump(GameState game, MonsterState monster) {
        PlayerState player = game.player;
        double dx = player.x - monster.x;
        double dy = player.y - monster.y;
        double dz = player.z - monster.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance <= 1.0E-12) return;

        double vx = dx / distance;
        double vy = dy / distance;
        double vz = dz / distance;

        if (game.kit == Kit.MAVERICK && game.mode != me.monstermazeai.game.Mode.ORIGINAL
                && game.activePadRow >= 0 && game.activePadColumn >= 0) {
            // QOL Maverick redirects the knockback trajectory toward the active
            // Safe Pad instead of using monster -> player geometry.
            double tx = game.activePadRow + 0.5 - player.x;
            double tz = game.activePadColumn + 0.5 - player.z;
            double len = Math.hypot(tx, tz);
            if (len > 1.0E-9) {
                vx = tx / len;
                vz = tz / len;
                vy = 0.0;
            }
        }

        vy += 0.75;
        if (vy > 1.2) vy = 1.2;
        if (player.grounded) vy += 0.2;

        player.vx = vx;
        player.vy = vy;
        player.vz = vz;
        player.pendingAirborne = true;
        player.health -= DAMAGE;
        player.damageTaken += DAMAGE;
        player.recentMobHitUntilTick = game.tick + RECHARGE_TICKS;
    }

    private static void applyNormalBump(PlayerState player, MonsterState monster, long tick) {
        double dx = player.x - monster.x;
        double dy = player.y - monster.y;
        double dz = player.z - monster.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance <= 1.0E-12) return;

        double vx = dx / distance;
        double vy = dy / distance + 0.75;
        double vz = dz / distance;
        if (vy > 1.2) vy = 1.2;
        if (player.grounded) vy += 0.2;

        player.vx = vx;
        player.vy = vy;
        player.vz = vz;
        player.pendingAirborne = true;
        player.health -= DAMAGE;
        player.damageTaken += DAMAGE;
        player.recentMobHitUntilTick = tick + RECHARGE_TICKS;
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

    private static boolean contact(PlayerState player, MonsterState monster) {
        double dx = player.x - monster.x;
        double dy = player.y - monster.y;
        double dz = player.z - monster.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) < CONTACT_DISTANCE;
    }
}
