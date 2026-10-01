package me.monstermazeai.ml;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.planner.TacticalRouteSimulator;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * Optional JSONL recorder for simulator-generated ML training examples.
 *
 * Disabled by default. Enable with:
 *   -Dmonstermaze.ml.record=true
 *   -Dmonstermaze.ml.output=ml-data/route-training.jsonl
 */
public final class RouteLearningRecorder {
    private static final Object LOCK = new Object();
    private static BufferedWriter writer;
    private static Path outputPath;
    private static boolean shutdownHookInstalled;

    private RouteLearningRecorder() {}

    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("monstermaze.ml.record", "false"));
    }

    public static void record(GameState state, PlayerRoute route, Cell goal,
                              TacticalRouteSimulator.Result result) {
        if (!enabled() || state == null || route == null || result == null) return;

        try {
            synchronized (LOCK) {
                ensureWriter();
                double[] features = RouteLearningFeatures.extract(state, route, goal);
                double target = RouteLearningFeatures.target(state, route, result);
                String group = groupKey(state, route, goal);

                StringBuilder line = new StringBuilder(1024);
                line.append("{\"version\":1");
                line.append(",\"group\":\"").append(escape(group)).append("\"");
                line.append(",\"mode\":\"").append(escape(state.mode.name())).append("\"");
                line.append(",\"kit\":\"").append(escape(state.kit.name())).append("\"");
                line.append(",\"stage\":").append(state.stage);
                line.append(",\"tick\":").append(state.tick);
                line.append(",\"reached\":").append(result.reached());
                line.append(",\"arrival_ticks\":").append(result.reached() ? result.arrivalTicks() : -1);
                line.append(",\"remaining_health\":").append(number(result.remainingHealth()));
                line.append(",\"damage_taken\":").append(number(result.damageTaken()));
                line.append(",\"final_waypoint\":").append(result.finalWaypoint());
                line.append(",\"route_size\":").append(route.size());
                line.append(",\"gap_count\":").append(RouteLearningFeatures.gapCount(route));
                line.append(",\"turn_count\":").append(RouteLearningFeatures.turnCount(route));
                line.append(",\"target\":").append(number(target));
                line.append(",\"features\":").append(featuresJson(features));
                line.append("}\n");

                writer.write(line.toString());
                writer.flush();
            }
        } catch (IOException failure) {
            System.err.println("[MonsterMazeAI] ML recorder failed: "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    private static String groupKey(GameState state, PlayerRoute route, Cell goal) {
        return state.mode.name() + "|" + state.kit.name()
                + "|pattern=" + state.mazePattern
                + "|stage=" + state.stage
                + "|tick=" + state.tick
                + "|start=" + route.cells().get(0).row() + "," + route.cells().get(0).column()
                + "|goal=" + goal.row() + "," + goal.column();
    }

    private static void ensureWriter() throws IOException {
        if (writer != null) return;
        outputPath = Path.of(System.getProperty(
                "monstermaze.ml.output", "ml-data/route-training.jsonl"));
        Path parent = outputPath.getParent();
        if (parent != null) Files.createDirectories(parent);

        writer = Files.newBufferedWriter(
                outputPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE);

        if (!shutdownHookInstalled) {
            Runtime.getRuntime().addShutdownHook(new Thread(RouteLearningRecorder::close,
                    "MonsterMaze-ml-recorder-shutdown"));
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
                // Best-effort shutdown flush.
            } finally {
                writer = null;
            }
        }
    }

    private static String featuresJson(double[] features) {
        StringBuilder out = new StringBuilder(features.length * 12 + 2);
        out.append('[');
        for (int i = 0; i < features.length; i++) {
            if (i > 0) out.append(',');
            out.append(number(features[i]));
        }
        out.append(']');
        return out.toString();
    }

    private static String number(double value) {
        if (!Double.isFinite(value)) return "0.0";
        return String.format(Locale.ROOT, "%.8f", value);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace(""", "\\"");
    }
}
