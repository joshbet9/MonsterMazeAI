package me.monstermazeai.ml;

import me.monstermazeai.game.GameState;
import me.monstermazeai.player.Action;
import me.monstermazeai.player.AiProfile;
import me.monstermazeai.planner.LiveObjectiveController;
import me.monstermazeai.planner.MazeAwareRecedingHorizonController;
import me.monstermazeai.planner.RobustLiveController;
import me.monstermazeai.runtime.AutonomousMonsterMazeAgent;
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
 * Each sampled state is treated as a decision point. Every executable candidate
 * receives a one-tick intervention in the source-faithful simulator, after
 * which the trusted high-skill controller continues the branch. The label is
 * the exact discounted reward accumulated over the simulated future.
 *
 * This is deliberately counterfactual: the learner sees measured outcomes for
 * actions that were not actually taken, rather than treating the current
 * controller's chosen action as ground truth.
 */
public final class CounterfactualPolicyLearningRecorder {
    private static final Object LOCK = new Object();
    private static BufferedWriter writer;
    private static Path writerPath;
    private static long rowCount;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (LOCK) {
                if (writer == null) return;
                try {
                    writer.flush();
                    writer.close();
                } catch (IOException ignored) {
                    // The JVM is already shutting down; there is no useful
                    // recovery action available here.
                }
            }
        }, "monstermaze-counterfactual-flush"));
    }

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
                System.getProperty("monstermaze.ml.policy.counterfactual.horizon", "128"),
                64);
        double gamma = finiteDouble(
                System.getProperty("monstermaze.ml.policy.counterfactual.gamma", "0.995"),
                0.995);

        // A phase transition requires the external harness to install/select
        // the next pad. Avoid creating labels from an intentionally incomplete
        // transition state when the actual observed state has no preview pad.
        if (state.previewPadRequested && state.previewPadRow < 0) return;
        if (state.phaseTicksRemaining <= 40 && state.previewPadRow < 0) return;

        String configured = System.getProperty("monstermaze.ml.policy.output", "").trim();
        if (configured.isEmpty()) return;

        List<Action> candidates = PolicyActionSelector.candidates(
                baseline, allowJump, state);
        if (candidates.isEmpty()) return;

        ensureWriter(Path.of(configured));

        String episode = state.mode.name()
                + "|pattern=" + state.mazePattern
                + "|kit=" + state.kit
                + "|seedOffset=" + Long.getLong(
                        "monstermaze.sim.seedOffset", 0L);

        long baseSeed = mix(simulator.monsterSeed() ^ state.tick
                ^ ((long) state.stage << 32)
                ^ ((long) state.mazePattern * 0x9E3779B97F4A7C15L));

        int candidateIndex = 0;
        for (Action candidate : candidates) {
            AutonomousMonsterMazeAgent continuation =
                    newPolicyContinuationAgent();

            String oldExplore = System.getProperty("monstermaze.ml.policy.explore");
            try {
                // Counterfactual labels must be deterministic: the rollout
                // continuation follows the current policy greedily.
                System.setProperty("monstermaze.ml.policy.explore", "false");

                List<GameState> snapshots = simulator.forecastCounterfactual(
                    state,
                    candidate,
                    horizon,
                    baseSeed,
                    continuationState -> continuation.decide(
                            continuationState,
                            allowJump));

                double target = discountedReward(state, snapshots, gamma);
                writeRow(
                        episode,
                        state.tick,
                        candidateIndex,
                        target,
                        PolicyLearningFeatures.extract(state, candidate));
            } finally {
                if (oldExplore == null) {
                    System.clearProperty("monstermaze.ml.policy.explore");
                } else {
                    System.setProperty("monstermaze.ml.policy.explore", oldExplore);
                }
            }

            candidateIndex++;
        }
    }

    private static AutonomousMonsterMazeAgent newPolicyContinuationAgent() {
        // In later policy-iteration cycles the selector will use the current
        // promoted model here. During the first cycle it naturally falls back
        // to the deterministic high-skill controller because no model exists.
        return new AutonomousMonsterMazeAgent(
                new RobustLiveController(
                        new LiveObjectiveController(
                                new MazeAwareRecedingHorizonController(
                                        1, AiProfile.HIGH_SKILL))));
    }

    private static double discountedReward(
            GameState before,
            List<GameState> snapshots,
            double gamma) {
        double total = 0.0;
        GameState previous = before;
        double discount = 1.0;

        for (GameState current : snapshots) {
            total += discount * PolicyLearningFeatures.reward(previous, current);
            discount *= gamma;
            previous = current;
            if (!current.alive || current.completed) break;
        }

        return total;
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
                        path,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
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
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private static String format(double value) {
        return Double.isFinite(value) ? Double.toString(value) : "0.0";
    }
}
