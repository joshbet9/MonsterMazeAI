package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiAttributes;
import me.monstermazeai.player.AiProfile;
import me.monstermazeai.player.AiTendencies;
import org.junit.jupiter.api.Test;

class SpeedPositiveKnockbackTuningTest {
    private static final int[][] CASES = {
            {0, Kit.JUMPER.ordinal()}, {0, Kit.SLOWBALLER.ordinal()},
            {0, Kit.BODY_BUILDER.ordinal()}, {0, Kit.REPULSOR.ordinal()},
            {0, Kit.MAVERICK.ordinal()},
            {1, Kit.JUMPER.ordinal()}, {1, Kit.SLOWBALLER.ordinal()},
            {1, Kit.BODY_BUILDER.ordinal()}, {1, Kit.REPULSOR.ordinal()},
            {1, Kit.MAVERICK.ordinal()},
            {2, Kit.JUMPER.ordinal()}, {2, Kit.SLOWBALLER.ordinal()},
            {2, Kit.BODY_BUILDER.ordinal()}, {2, Kit.REPULSOR.ordinal()},
            {2, Kit.MAVERICK.ordinal()}
    };

    @Test
    void sweepPositiveMobKnockbackOnSpeedCases() {
        double[] values = {0.25, 0.40, 0.55, 0.70, 0.85};
        for (double value : values) {
            int total = 0, stage10 = 0, stage15 = 0;
            for (int[] testCase : CASES) {
                int pattern = testCase[0];
                Kit kit = Kit.values()[testCase[1]];
                AiProfile profile = new AiProfile(
                        new AiAttributes(1.0, 0.85, 0.90, 0.90),
                        new AiTendencies(
                                AiTendencies.DirectionChangeType.MIXED,
                                0.70, value, 0.90, 0.85, 0.80, 0.80));
                AuthenticStage10SimulationTest.RunResult result =
                        AuthenticStage10SimulationTest.runDiagnostic(
                                pattern, kit, profile, Mode.SPEED, 20);
                total += result.maxStage();
                if (result.maxStage() >= 10) stage10++;
                if (result.maxStage() >= 15) stage15++;
                System.out.printf(
                        "SPEED_POS_KB value=%.2f pattern=%d kit=%s stage=%d ticks=%d hp=%s fallTick=%d%n",
                        value, pattern + 1, kit, result.maxStage(), result.ticks(),
                        Double.toString(result.health()), result.firstFallTick());
            }
            System.out.printf(
                    "SPEED_POS_KB_SUMMARY value=%.2f total=%d average=%.3f stage10=%d/%d stage15=%d/%d%n",
                    value, total, total / (double) CASES.length, stage10, CASES.length,
                    stage15, CASES.length);
        }
    }
}
