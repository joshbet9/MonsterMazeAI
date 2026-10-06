package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import org.junit.jupiter.api.Test;

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

        AuthenticStage10SimulationTest.RunResult result =
                AuthenticStage10SimulationTest.run(
                        pattern, kit, AiProfile.HIGH_SKILL, mode, 0);

        String row = row(mode, pattern, kit, result);
        System.out.println(row);
        assertTrue(result.maxStage() >= 1);
    }

    private void runAll() {
        int count = 0;
        for (Mode mode : List.of(Mode.SPEED, Mode.MODERN)) {
            for (int pattern = 0; pattern < 3; pattern++) {
                for (Kit kit : Kit.values()) {
                    AuthenticStage10SimulationTest.RunResult result =
                            AuthenticStage10SimulationTest.run(
                                    pattern, kit, AiProfile.HIGH_SKILL, mode, 0);
                    System.out.println(row(mode, pattern, kit, result));
                    count++;
                }
            }
        }
        assertEquals(30, count);
    }

    private static String row(
            Mode mode,
            int pattern,
            Kit kit,
            AuthenticStage10SimulationTest.RunResult result) {
        return String.format(
                java.util.Locale.ROOT,
                "FULL_MATRIX mode=%s pattern=%d kit=%s stage=%d ticks=%d health=%.2f",
                mode, pattern + 1, kit, result.maxStage(),
                result.ticks(), result.health());
    }
}
