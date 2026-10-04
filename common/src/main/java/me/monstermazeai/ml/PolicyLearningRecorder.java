package me.monstermazeai.ml;

import me.monstermazeai.game.GameState;
import me.monstermazeai.player.Action;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * Recorder for long-horizon state/action/reward transitions.
 */
public final class PolicyLearningRecorder {
    private static final Object LOCK = new Object();
    private static BufferedWriter writer;
    private static boolean shutdownHookInstalled;

    private PolicyLearningRecorder() {}

    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("monstermaze.ml.policy.record", "false"));
    }

    public static void record(GameState before, Action action, GameState after) {
        if (!enabled() || before == null || action == null || after == null) return;

        try {
            synchronized (LOCK) {
                ensureWriter();

                double[] features = PolicyLearningFeatures.extract(before, action);
                double reward = PolicyLearningFeatures.reward(before, after);
                boolean done = PolicyLearningFeatures.done(after);
                String episode = before.mode.name() + "|pattern=" + before.mazePattern
                        + "|kit=" + before.kit.name()
                        + "|seedOffset=" + Long.getLong("monstermaze.sim.seedOffset", 0L);

                StringBuilder line = new StringBuilder(1400);
                line.append("{\"version\":1");
                line.append(",\"episode\":\"").append(escape(episode)).append("\"");
                line.append(",\"t\":").append(before.tick);
                line.append(",\"reward\":").append(number(reward));
                line.append(",\"done\":").append(done);
                line.append(",\"stage\":").append(before.stage);
                line.append(",\"features\":").append(featuresJson(features));
                line.append("}\n");

                writer.write(line.toString());
                writer.flush();
            }
        } catch (IOException failure) {
            System.err.println("[MonsterMazeAI] Policy recorder failed: "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    private static void ensureWriter() throws IOException {
        if (writer != null) return;
        Path output = Path.of(System.getProperty(
                "monstermaze.ml.policy.output", "ml-data/policy-training.jsonl"));
        Path parent = output.getParent();
        if (parent != null) Files.createDirectories(parent);

        writer = Files.newBufferedWriter(
                output,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE);

        if (!shutdownHookInstalled) {
            Runtime.getRuntime().addShutdownHook(new Thread(PolicyLearningRecorder::close,
                    "MonsterMaze-policy-recorder-shutdown"));
            shutdownHookInstalled = true;
        }
    }

    private static void close() {
        synchronized (LOCK) {
            if (writer == null) return;
            try {
                writer.flush();
                writer.close();
            } catch (IOException ignored) {
                // Best effort at JVM shutdown.
            } finally {
                writer = null;
            }
        }
    }

    private static String featuresJson(double[] values) {
        StringBuilder out = new StringBuilder(values.length * 12 + 2);
        out.append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append(',');
            out.append(number(values[i]));
        }
        out.append(']');
        return out.toString();
    }

    private static String number(double value) {
        if (!Double.isFinite(value)) return "0.0";
        return String.format(Locale.ROOT, "%.8f", value);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
