package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import org.junit.jupiter.api.Test;

/**
 * Focused diagnostic runner for the current Speed cases that missed Stage 10.
 * It intentionally contains no acceptance assertion so the trace remains
 * available while movement tuning is in progress.
 */
class SpeedFailureDiagnosticTest {
    @Test
    void currentKnownSpeedFailures() {
        int[][] cases = {
                {0, Kit.REPULSOR.ordinal()},
                {0, Kit.MAVERICK.ordinal()},
                {1, Kit.REPULSOR.ordinal()},
                {2, Kit.REPULSOR.ordinal()},
                {2, Kit.MAVERICK.ordinal()}
        };

        for (int[] testCase : cases) {
            int pattern = testCase[0];
            Kit kit = Kit.values()[testCase[1]];
            AuthenticStage10SimulationTest.RunResult result =
                    AuthenticStage10SimulationTest.runDiagnostic(
                            pattern, kit, AiProfile.HIGH_SKILL, Mode.SPEED);
            System.out.printf(
                    "DIAGNOSTIC SPEED pattern=%d kit=%s stage=%d tick=%d hp=%s fallTick=%d%n",
                    pattern + 1, kit, result.maxStage(), result.ticks(),
                    Double.toString(result.health()), result.firstFallTick());
            System.out.println(result.firstFallDecision());
        }
    }
}
