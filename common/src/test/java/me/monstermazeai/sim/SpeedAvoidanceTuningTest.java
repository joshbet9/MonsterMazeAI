package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import org.junit.jupiter.api.Test;

class SpeedAvoidanceTuningTest {
    private static final int[][] CASES = {
            {0, Kit.JUMPER.ordinal()},
            {0, Kit.REPULSOR.ordinal()},
            {1, Kit.SLOWBALLER.ordinal()},
            {1, Kit.BODY_BUILDER.ordinal()},
            {2, Kit.REPULSOR.ordinal()},
            {2, Kit.MAVERICK.ordinal()}
    };

    @Test
    void evaluateReactionsAwareMonsterAvoidance() {
        AiProfile profile = AiProfile.HIGH_SKILL;
        int total = 0;
        int stage10 = 0;
        int stage15 = 0;

        for (int[] testCase : CASES) {
            Kit kit = Kit.values()[testCase[1]];
            AuthenticStage10SimulationTest.RunResult result =
                    AuthenticStage10SimulationTest.runToEnd(
                            testCase[0], kit, profile, Mode.SPEED);
            total += result.maxStage();
            if (result.maxStage() >= 10) stage10++;
            if (result.maxStage() >= 15) stage15++;
            System.out.printf(
                    "SPEED_AVOIDANCE_TUNING pattern=%d kit=%s stage=%d ticks=%d hp=%s fallTick=%d%n",
                    testCase[0] + 1, kit, result.maxStage(), result.ticks(),
                    Double.toString(result.health()), result.firstFallTick());
        }

        System.out.printf(
                "SPEED_AVOIDANCE_TUNING_SUMMARY average=%.3f stage10=%d/%d stage15=%d/%d%n",
                total / (double) CASES.length, stage10, CASES.length, stage15, CASES.length);
    }
}
