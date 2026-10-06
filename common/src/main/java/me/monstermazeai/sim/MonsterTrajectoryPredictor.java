package me.monstermazeai.sim;

import me.monstermazeai.game.GameState;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.player.Action;

import java.util.List;

/**
 * Short-horizon, source-model monster prediction.
 *
 * Unlike the old straight-line heuristic, this runs the actual MonsterSimulator
 * through Simulator.forecastSnapshots. Intersections, direction changes,
 * frozen/removed monsters and launched monsters therefore use the same rules
 * as normal simulation.
 */
public final class MonsterTrajectoryPredictor {
    private static final double DANGER_DISTANCE_SQ = 1.0;

    private final Simulator simulator;

    public MonsterTrajectoryPredictor(Simulator simulator) {
        this.simulator = simulator;
    }

    public Prediction predict(GameState source, Action[] actions, long seed) {
        List<GameState> snapshots = simulator.forecastSnapshots(source, actions, seed);

        double minimumDistanceSq = Double.POSITIVE_INFINITY;
        int firstDangerTick = -1;
        double expectedDamage = 0.0;

        for (int i = 0; i < snapshots.size(); i++) {
            GameState state = snapshots.get(i);
            for (MonsterState monster : state.monsters) {
                if (monster.removed || monster.launched(state.tick)
                        || monster.frozen(state.tick)) {
                    continue;
                }

                double dx = state.player.x - monster.x;
                double dy = state.player.y - monster.y;
                double dz = state.player.z - monster.z;
                double distanceSq = dx * dx + dy * dy + dz * dz;
                minimumDistanceSq = Math.min(minimumDistanceSq, distanceSq);

                if (distanceSq < DANGER_DISTANCE_SQ && firstDangerTick < 0) {
                    firstDangerTick = i + 1;
                }
            }
        }

        GameState finalState = snapshots.isEmpty()
                ? source
                : snapshots.get(snapshots.size() - 1);
        expectedDamage = Math.max(0.0,
                finalState.player.damageTaken - source.player.damageTaken);

        return new Prediction(
                snapshots.size(),
                minimumDistanceSq,
                firstDangerTick,
                expectedDamage,
                !snapshots.isEmpty() && finalState.alive,
                !snapshots.isEmpty() && finalState.padReached);
    }

    public Prediction predict(GameState source, Action repeatedAction,
                              int horizon, long seed) {
        Action[] actions = new Action[horizon];
        java.util.Arrays.fill(actions, repeatedAction);
        return predict(source, actions, seed);
    }

    public record Prediction(
            int ticks,
            double minimumDistanceSquared,
            int firstDangerTick,
            double damageTaken,
            boolean survived,
            boolean padReached) {

        public boolean collisionRisk() {
            return firstDangerTick >= 0;
        }

        public double riskCost() {
            if (damageTaken <= 0.0 && !collisionRisk()) return 0.0;
            double timing = firstDangerTick < 0 ? 1.0 : 1.0 + (ticks - firstDangerTick) * 0.05;
            return damageTaken * timing + (collisionRisk() ? 1.0 : 0.0);
        }
    }
}
