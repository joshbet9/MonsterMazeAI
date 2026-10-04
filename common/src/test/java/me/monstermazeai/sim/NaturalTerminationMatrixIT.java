package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NaturalTerminationMatrixIT {
    @Test
    void naturalTerminationCase() {
        String modeValue = System.getProperty("matrixMode");
        String patternValue = System.getProperty("matrixPattern");
        String kitValue = System.getProperty("matrixKit");

        if (modeValue == null || patternValue == null || kitValue == null) {
            throw new IllegalArgumentException("matrixMode, matrixPattern and matrixKit are required");
        }

        Mode mode = Mode.valueOf(modeValue);
        int pattern = Integer.parseInt(patternValue) - 1;
        Kit kit = Kit.valueOf(kitValue);

        AuthenticStage10SimulationTest.RunResult result =
                AuthenticStage10SimulationTest.run(
                        pattern, kit, AiProfile.HIGH_SKILL, mode, 0);

        System.out.printf(
                "FULL_MATRIX mode=%s pattern=%d kit=%s stage=%d ticks=%d health=%.2f%n",
                mode, pattern + 1, kit, result.maxStage(),
                result.ticks(), result.health());
        assertTrue(result.maxStage() >= 1);
    }
}
