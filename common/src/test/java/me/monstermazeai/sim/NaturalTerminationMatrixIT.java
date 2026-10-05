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
        System.out.printf(
                java.util.Locale.ROOT,
                "EFFICIENCY mode=%s pattern=%d kit=%s s5k=%d s10k=%d s15k=%d s20k=%d%n",
                mode, pattern + 1, kit,
                result.stageAt5k(),
                result.stageAt10k(),
                result.stageAt15k(),
                result.stageAt20k());
        System.setProperty("matrixRow", row);
        assertTrue(result.maxStage() >= 1);
    }

    private void runAll() {
        boolean spawnMonsters = Boolean.parseBoolean(
                System.getProperty("matrixSpawnMonsters", "true"));
        List<String> rows = new ArrayList<>();
        for (Mode mode : List.of(Mode.SPEED, Mode.MODERN))
            for (int pattern = 0; pattern < 3; pattern++) {
                for (Kit kit : Kit.values()) {
                    AuthenticStage10SimulationTest.RunResult result =
                            AuthenticStage10SimulationTest.run(
                                    pattern, kit, AiProfile.HIGH_SKILL, mode, 0, spawnMonsters);
                    String row = String.format(
                            java.util.Locale.ROOT,
                            "FULL_MATRIX mode=%s pattern=%d kit=%s stage=%d ticks=%d health=%.2f",
                            mode, pattern + 1, kit, result.maxStage(),
                            result.ticks(), result.health());
                    System.out.println(row);
                    System.out.printf(
                            java.util.Locale.ROOT,
                            "EFFICIENCY mode=%s pattern=%d kit=%s s5k=%d s10k=%d s15k=%d s20k=%d%n",
                            mode, pattern + 1, kit,
                            result.stageAt5k(),
                            result.stageAt10k(),
                            result.stageAt15k(),
                            result.stageAt20k());
                    rows.add(row);
                }
            }
        assertEquals(30, rows.size());
    }
}
