package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Progress metric for the full source matrix.
 *
 * This is intentionally diagnostic rather than a hard acceptance gate:
 * every Pattern x Mode x Kit cell is evaluated toward stage 40 and the
 * complete natural-termination tests remain responsible for reporting the
 * actual ceiling. The metric makes progress toward the development target
 * visible without turning a single stage threshold into the training limit.
 */
class MatrixProgression40DiagnosticTest {
    private static final int TARGET_STAGE = 40;

    @Test
    void fullMatrixProgressTowardStage40() {
        List<String> rows = new ArrayList<>();
        int reachedTarget = 0;
        int total = 0;
        int minStage = Integer.MAX_VALUE;
        int maxStage = Integer.MIN_VALUE;
        long stageSum = 0L;

        for (Mode mode : new Mode[]{Mode.MODERN, Mode.SPEED}) {
            for (int pattern = 0; pattern < 3; pattern++) {
                for (Kit kit : Kit.values()) {
                    AuthenticStage10SimulationTest.RunResult result =
                            AuthenticStage10SimulationTest.runDiagnostic(
                                    pattern, kit, AiProfile.HIGH_SKILL, mode, TARGET_STAGE);

                    total++;
                    minStage = Math.min(minStage, result.maxStage());
                    maxStage = Math.max(maxStage, result.maxStage());
                    stageSum += result.maxStage();
                    if (result.maxStage() >= TARGET_STAGE) reachedTarget++;

                    rows.add(String.format(
                            java.util.Locale.ROOT,
                            "MATRIX40 mode=%s pattern=%d kit=%s stage=%d ticks=%d fall=%d health=%.1f",
                            mode, pattern + 1, kit, result.maxStage(), result.ticks(),
                            result.firstFallTick(), result.health()));
                }
            }
        }

        rows.forEach(System.out::println);
        System.out.printf(
                java.util.Locale.ROOT,
                "MATRIX40_SUMMARY target=%d reached=%d total=%d coverage=%.3f meanStage=%.2f minStage=%d maxStage=%d%n",
                TARGET_STAGE, reachedTarget, total,
                reachedTarget / (double) total,
                stageSum / (double) total, minStage, maxStage);
    }
}
