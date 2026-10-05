package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NaturalTerminationMatrixIT {
    @Test
    void naturalTerminationCase() {
        String modeValue = System.getProperty("matrixMode");
        String patternValue = System.getProperty("matrixPattern");
        String kitValue = System.getProperty("matrixKit");

        if (modeValue == null || patternValue == null || kitValue == null) {
            runAll();
            return;
        }

        Mode mode = Mode.valueOf(modeValue);
        int pattern = Integer.parseInt(patternValue) - 1;
        Kit kit = Kit.valueOf(kitValue);
        boolean spawnMonsters = Boolean.parseBoolean(
                System.getProperty("matrixSpawnMonsters", "true"));
        AuthenticStage10SimulationTest.RunResult result =
                AuthenticStage10SimulationTest.run(
                        pattern, kit, AiProfile.HIGH_SKILL, mode, 0, spawnMonsters);

        String row = String.format(
                java.util.Locale.ROOT,
                "FULL_MATRIX mode=%s pattern=%d kit=%s stage=%d ticks=%d health=%.2f",
                mode, pattern + 1, kit, result.maxStage(),
                result.ticks(), result.health());

        System.out.println(row);
        System.setProperty("matrixRow", row);
        assertTrue(result.maxStage() >= 1);
    }

    private void runAll() {
        List<String> rows = new ArrayList<>();
        for (Mode mode : List.of(Mode.SPEED, Mode.MODERN)) {
            for (int pattern = 0; pattern < 3; pattern++) {
                for (Kit kit : Kit.values()) {
                    AuthenticStage10SimulationTest.RunResult result =
                            AuthenticStage10SimulationTest.run(
                                    pattern, kit, AiProfile.HIGH_SKILL, mode, 0, true);
                    String row = String.format(
                            java.util.Locale.ROOT,
                            "FULL_MATRIX mode=%s pattern=%d kit=%s stage=%d ticks=%d health=%.2f",
                            mode, pattern + 1, kit, result.maxStage(),
                            result.ticks(), result.health());
                    System.out.println(row);
                    rows.add(row);
                }
            }
        }
        assertEquals(30, rows.size());
    }
}
