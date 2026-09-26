package me.monstermazeai.physics;

import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.player.PlayerState;

import java.util.List;

/**
 * Source-faithful Monster Maze player bump.
 *
 * Maze.bump checks strict 3-D distance < 1, applies
 * UtilAction.velocity(player, UtilAlg.getTrajectory(monster, player),
 * 1, false, 0, 0.75, 1.2, true), and deals exactly four damage.
 *
 * The source's "Monster Hit" recharge is wall-clock based at 1000 ms.
 * The deterministic 20 TPS simulator represents that as 20 ticks.
 *
 * Maze.bump does not exclude frozen/launched monsters from its active entity
 * loop; only removed entities are absent from that map.
 */
public final class MonsterMazeBumpModel {
    public static final double CONTACT_DISTANCE = 1.0;
    public static final double DAMAGE = 4.0;
    public static final long RECHARGE_TICKS = 20L;
    private static final double Y_ADD = 0.75;
    private static final double Y_MAX = 1.2;
    private static final double GROUND_BOOST = 0.2;

    private MonsterMazeBumpModel() {}

    public static int apply(PlayerState player, List<MonsterState> monsters, long tick) {
        if (player.recentMobHitUntilTick > tick || player.health <= 0.0) return 0;

        int hits = 0;
        for (MonsterState monster : monsters) {
            if (monster.removed) continue;

            double dx = player.x - monster.x;
            double dy = player.y - monster.y;
            double dz = player.z - monster.z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (!(distance < CONTACT_DISTANCE) || distance <= 1.0E-12) continue;

            double vx = dx / distance;
            double vy = dy / distance;
            double vz = dz / distance;

            vy += Y_ADD;
            if (vy > Y_MAX) vy = Y_MAX;
            if (player.grounded) vy += GROUND_BOOST;

            player.vx = vx;
            player.vy = vy;
            player.vz = vz;
            player.pendingAirborne = true;

            player.health -= DAMAGE;
            player.damageTaken += DAMAGE;
            player.recentMobHitUntilTick = tick + RECHARGE_TICKS;
            hits++;
        }
        return hits;
    }
}
