package me.monstermazeai.ml;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class RouteValueModelTest {
    @Test
    void generatedModelJsonCanBeLoaded() throws Exception {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"input_mean\":").append(array(34, 0.0)).append(",");
        json.append("\"input_std\":").append(array(34, 1.0)).append(",");
        json.append("\"target_mean\":10,");
        json.append("\"target_std\":2,");
        json.append("\"w1\":").append(matrix(34, 32)).append(",");
        json.append("\"b1\":").append(array(32, 0.0)).append(",");
        json.append("\"w2\":").append(matrix(32, 16)).append(",");
        json.append("\"b2\":").append(array(16, 0.0)).append(",");
        json.append("\"w3\":").append(array(16, 0.0)).append(",");
        json.append("\"b3\":").append(array(1, 0.0));
        json.append("}");

        Path path = Files.createTempFile("monstermaze-model-", ".json");
        Files.writeString(path, json);
        try {
            RouteValueModel model = RouteValueModel.load(path);
            assertNotNull(model);
            assertEquals(10.0, model.predict(new double[34]), 1e-9);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static String array(int count, double value) {
        double[] values = new double[count];
        Arrays.fill(values, value);
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append(',');
            out.append(values[i]);
        }
        return out.append(']').toString();
    }

    private static String matrix(int rows, int columns) {
        StringBuilder out = new StringBuilder("[");
        for (int r = 0; r < rows; r++) {
            if (r > 0) out.append(',');
            out.append(array(columns, 0.0));
        }
        return out.append(']').toString();
    }
}
