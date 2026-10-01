package me.monstermazeai.ml;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.PlayerRoute;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small dependency-free inference engine for the offline route-value model.
 *
 * Loading is opt-in through -Dmonstermaze.ml.model=<path>. It is shadow-only
 * until a caller explicitly consumes the prediction for a gameplay decision.
 */
public final class RouteValueModel {
    private static final int INPUTS = 34;
    private static final int HIDDEN_1 = 32;
    private static final int HIDDEN_2 = 16;

    private final double[] inputMean;
    private final double[] inputStd;
    private final double targetMean;
    private final double targetStd;
    private final double[][] w1;
    private final double[] b1;
    private final double[][] w2;
    private final double[] b2;
    private final double[] w3;
    private final double b3;

    private RouteValueModel(double[] inputMean, double[] inputStd,
                            double targetMean, double targetStd,
                            double[][] w1, double[] b1,
                            double[][] w2, double[] b2,
                            double[] w3, double b3) {
        this.inputMean = inputMean;
        this.inputStd = inputStd;
        this.targetMean = targetMean;
        this.targetStd = targetStd;
        this.w1 = w1;
        this.b1 = b1;
        this.w2 = w2;
        this.b2 = b2;
        this.w3 = w3;
        this.b3 = b3;
    }

    public static RouteValueModel disabled() {
        return null;
    }

    public static RouteValueModel loadFromProperty() {
        String configured = System.getProperty("monstermaze.ml.model", "").trim();
        if (configured.isEmpty()) return null;
        try {
            return load(Path.of(configured));
        } catch (RuntimeException | IOException failure) {
            System.err.println("[MonsterMazeAI] ML route model disabled: "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            return null;
        }
    }

    public static RouteValueModel load(Path path) throws IOException {
        if (path == null) throw new IllegalArgumentException("path");
        String json = Files.readString(path, StandardCharsets.UTF_8);

        double[] inputMean = array(json, "input_mean", INPUTS);
        double[] inputStd = array(json, "input_std", INPUTS);
        double targetMean = scalar(json, "target_mean");
        double targetStd = scalar(json, "target_std");
        double[][] w1 = matrix(json, "w1", INPUTS, HIDDEN_1);
        double[] b1 = array(json, "b1", HIDDEN_1);
        double[][] w2 = matrix(json, "w2", HIDDEN_1, HIDDEN_2);
        double[] b2 = array(json, "b2", HIDDEN_2);
        double[] w3 = array(json, "w3", HIDDEN_2);
        double b3 = array(json, "b3", 1)[0];

        for (double value : inputStd) {
            if (!Double.isFinite(value) || Math.abs(value) < 1.0E-12) {
                throw new IllegalArgumentException("Invalid input standard deviation");
            }
        }
        if (!Double.isFinite(targetStd) || Math.abs(targetStd) < 1.0E-12) {
            throw new IllegalArgumentException("Invalid target standard deviation");
        }

        return new RouteValueModel(inputMean, inputStd, targetMean, targetStd,
                w1, b1, w2, b2, w3, b3);
    }

    public double predict(GameState state, PlayerRoute route, Cell goal) {
        return predict(RouteLearningFeatures.extract(state, route, goal));
    }

    public double predict(double[] features) {
        if (features == null || features.length != INPUTS) {
            throw new IllegalArgumentException("Expected " + INPUTS + " features");
        }

        double[] a1 = new double[HIDDEN_1];
        for (int j = 0; j < HIDDEN_1; j++) {
            double sum = b1[j];
            for (int i = 0; i < INPUTS; i++) {
                sum += ((features[i] - inputMean[i]) / inputStd[i]) * w1[i][j];
            }
            a1[j] = Math.max(0.0, sum);
        }

        double[] a2 = new double[HIDDEN_2];
        for (int j = 0; j < HIDDEN_2; j++) {
            double sum = b2[j];
            for (int i = 0; i < HIDDEN_1; i++) sum += a1[i] * w2[i][j];
            a2[j] = Math.max(0.0, sum);
        }

        double output = b3;
        for (int i = 0; i < HIDDEN_2; i++) output += a2[i] * w3[i];
        return output * targetStd + targetMean;
    }

    private static double scalar(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*([-+0-9.eE]+)")
                .matcher(json);
        if (!matcher.find()) throw new IllegalArgumentException("Missing scalar: " + key);
        return Double.parseDouble(matcher.group(1));
    }

    private static double[] array(String json, String key, int expected) {
        String body = bracketBody(json, key, '[', ']');

        // Accept both a canonical flat numeric array and the nested [N][1]
        // representation emitted by older versions of the Python trainer.
        // Extracting numeric literals instead of splitting on commas also makes
        // this loader tolerant of pretty-printed JSON.
        Matcher matcher = Pattern.compile(
                "[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?")
                .matcher(body);

        double[] out = new double[expected];
        int count = 0;
        while (matcher.find()) {
            if (count >= expected) {
                throw new IllegalArgumentException("Field " + key + " expected "
                        + expected + " values, got more");
            }
            out[count++] = Double.parseDouble(matcher.group());
        }

        if (count != expected) {
            throw new IllegalArgumentException("Field " + key + " expected " + expected
                    + " values, got " + count);
        }
        return out;
    }

    private static double[][] matrix(String json, String key, int rows, int columns) {
        String body = bracketBody(json, key, '[', ']');
        double[][] out = new double[rows][columns];
        int row = 0;
        int depth = 0;
        int start = -1;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '[') {
                if (depth == 0) start = i + 1;
                depth++;
            } else if (ch == ']') {
                depth--;
                if (depth == 0) {
                    if (row >= rows) throw new IllegalArgumentException("Too many rows in " + key);
                    String[] values = body.substring(start, i).split(",");
                    if (values.length != columns) {
                        throw new IllegalArgumentException("Row " + row + " of " + key
                                + " expected " + columns + " values, got " + values.length);
                    }
                    for (int col = 0; col < columns; col++) {
                        out[row][col] = Double.parseDouble(values[col].trim());
                    }
                    row++;
                }
            }
        }
        if (row != rows) throw new IllegalArgumentException("Field " + key + " expected "
                + rows + " rows, got " + row);
        return out;
    }

    private static String bracketBody(String json, String key, char open, char close) {
        String marker = "\"" + key + "\"";
        int markerIndex = json.indexOf(marker);
        if (markerIndex < 0) throw new IllegalArgumentException("Missing field: " + key);
        int start = json.indexOf(open, markerIndex);
        if (start < 0) throw new IllegalArgumentException("Missing array: " + key);

        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == open) depth++;
            else if (ch == close) {
                depth--;
                if (depth == 0) return json.substring(start + 1, i);
            }
        }
        throw new IllegalArgumentException("Unterminated array: " + key);
    }
}
