package me.monstermazeai.ml;

import me.monstermazeai.game.GameState;
import me.monstermazeai.player.Action;
import me.monstermazeai.sim.Simulator;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Records simulator-measured alternatives for policy learning.
 *
 * At sampled live states, every executable candidate action is simulated for a
 * short source-faithful horizon. The recorded target is therefore the measured
 * future outcome of an action that was NOT actually taken, which turns the
 * simulator into the teacher instead of treating the current policy's choices
 * as ground truth.
 */
public final class CounterfactualPolicyLearningRecorder {
    private static final Object LOCK = new Object();
    private static BufferedWriter writer;
    private static Path writerPath;
    private static long rowCount;

    private CounterfactualPolicyLearningRecorder() {}

    public static boolean enabled() {
        return Boolean.parseBoolean(
                System.getProperty("monstermaze.ml.policy.counterfactual", "false"));
    }

    public static void record(GameState state, Action baseline, boolean allowJump,
                              Simulator simulator) {
        if (!enabled() || state == null || baseline == null || simulator == null) return;

        int stride = positiveInt(
                System.getProperty("monstermaze.ml.policy.counterfactual.stride", "20"),
                20);
        if (state.tick % stride != 0) return;

        int horizon = positiveInt(
                System.getProperty("monstermaze.ml.policy.counterfactual.horizon", "32"),
                32);
        double gamma = finiteDouble(
                System.getProperty("monstermaze.ml.policy.counterfactual.gamma", "0.995"),
                0.995);

        String configured = System.getProperty("monstermaze.ml.policy.output", "").trim();
        if (configured.isEmpty()) return;

        List<Action> candidates = PolicyActionSelector.candidates(baseline, allowJump, state);
        if (candidates.isEmpty()) return;

        ensureWriter(Path.of(configured));

        String episode = state.mode.name()
                + "|pattern=" + state.mazePattern
                + "|kit=" + state.kit
                + "|seedOffset=" + Long.getLong("monstermaze.sim.seedOffset", 0L);

        long baseSeed = mix(simulator.monsterSeed() ^ state.tick
                ^ ((long) state.stage << 32)
                ^ ((long) state.mazePattern * 0x9E3779B97F4A7C15L));

        int candidateIndex = 0;
        for (Action candidate : candidates) {
            Action[] actions = new Action[horizon];
            actions[0] = candidate;
            Action continuation = new Action(
                    candidate.forward(), candidate.strafe(), candidate.jump(),
                    candidate.sprint(), candidate.yawDelta(), false);
            for (int i = 1; i < horizon; i++) actions[i] = continuation;

            // All candidates use the same deterministic monster RNG stream so
            // the label difference comes from the action, not a different mob roll.
            GameState end = simulator.forecastUntilStageChange(state, actions, baseSeed);
            double target = shortHorizonReturn(state, end, actions.length, gamma);

            writeRow(episode, state.tick, candidateIndex, target,
                    PolicyLearningFeatures.extract(state, candidate));
            candidateIndex++;
        }
    }

    private static double shortHorizonReturn(GameState before, GameState after,
                                             int requestedHorizon, double gamma) {
        int elapsed = (int) Math.max(1L, after.tick - before.tick);
        double discountSum = gamma == 1.0
                ? elapsed
                : (1.0 - Math.pow(gamma, elapsed)) / (1.0 - gamma);

        double reward = 0.02 * discountSum;

        int stageDelta = Math.max(0, after.stage - before.stage);
        reward += stageDelta * 100.0;

        if (stageDelta == 0
                && before.activePadRow >= 0 && before.activePadColumn >= 0
                && before.activePadRow == after.activePadRow
                && before.activePadColumn == after.activePadColumn) {
            double beforeDx = before.activePadRow + 0.5 - before.player.x;
            double beforeDz = before.activePadColumn + 0.5 - before.player.z;
            double afterDx = after.activePadRow + 0.5 - after.player.x;
            double afterDz = after.activePadColumn + 0.5 - after.player.z;
            reward += Math.pow(gamma, Math.max(0, elapsed - 1))
                    * PolicyLearningFeatures.clamp(
                    beforeDistance(beforeDx, beforeDz) - beforeDistance(afterDx, afterDz),
                    -1.0, 1.0) * 0.5;
        }

        double damageDelta = Math.max(0.0,
                after.player.damageTaken - before.player.damageTaken);
        reward -= damageDelta * 1.5;

        if (after.player.y < GameState.PATH_Y - 0.05) reward -= 2.0;
        if (!after.alive) reward -= 100.0;

        return reward;
    }

    private static double beforeDistance(double x, double z) {
        return Math.hypot(x, z);
    }

    private static void writeRow(String episode, long tick, int candidate,
                                 double target, double[] features) {
        StringBuilder json = new StringBuilder(2048);
        json.append("{\"episode\":\"").append(escape(episode))
                .append("\",\"t\":").append(tick)
                .append(",\"candidate\":").append(candidate)
                .append(",\"target_return\":").append(format(target))
                .append(",\"features\":[");
        for (int i = 0; i < features.length; i++) {
            if (i > 0) json.append(',');
            json.append(format(features[i]));
        }
        json.append("]}\n");

        synchronized (LOCK) {
            try {
                writer.write(json.toString());
                rowCount++;
                if ((rowCount & 127L) == 0L) writer.flush();
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Cannot write counterfactual policy data", e);
            }
        }
    }

    private static void ensureWriter(Path path) {
        synchronized (LOCK) {
            try {
                if (writer != null && path.equals(writerPath)) return;
                if (writer != null) writer.close();
                Path parent = path.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                writer = Files.newBufferedWriter(
                        path, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                writerPath = path;
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Cannot open counterfactual policy data: " + path, e);
            }
        }
    }

    private static int positiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static double finiteDouble(String value, double fallback) {
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return value;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String format(double value) {
        return Double.isFinite(value) ? Double.toString(value) : "0.0";
    }
}
