package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiAttributes;
import me.monstermazeai.player.AiProfile;
import me.monstermazeai.player.AiTendencies;
import org.junit.jupiter.api.Test;

class SpeedRepulsorIqTuningTest {
    private static final int[][] CASES = {
            {0, Kit.REPULSOR.ordinal()},
            {1, Kit.REPULSOR.ordinal()},
            {2, Kit.REPULSOR.ordinal()}
    };

    @Test
    void sweepRepulsorIq() {
        double[] values = {0.50, 0.65, 0.80, 0.95};
        for (double iq : values) {
            int total = 0;
            for (int[] testCase : CASES) {
                int pattern = testCase[0];
                Kit kit = Kit.values()[testCase[1]];
                AiProfile profile = new AiProfile(
                        new AiAttributes(1.0, 0.85, 0.90, 0.90),
                        new AiTendencies(
                                AiTendencies.DirectionChangeType.MIXED,
                                0.70, 0.55, 0.90, iq, 0.80, 0.80));
                AuthenticStage10SimulationTest.RunResult result =
                        AuthenticStage10SimulationTest.runDiagnostic(
                                pattern, kit, profile, Mode.SPEED, 20);
                total += result.maxStage();
                System.out.printf(
                        "SPEED_REPULSOR_IQ iq=%.2f pattern=%d stage=%d ticks=%d hp=%s fallTick=%d%n",
                        iq, pattern + 1, result.maxStage(), result.ticks(),
                        Double.toString(result.health()), result.firstFallTick());
            }
            System.out.printf("SPEED_REPULSOR_IQ_SUMMARY iq=%.2f total=%d average=%.3f%n",
                    iq, total, total / (double) CASES.length);
        }
    }
}
