package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiAttributes;
import me.monstermazeai.player.AiProfile;
import me.monstermazeai.player.AiTendencies;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

class SpeedGapRiskTuningTest {
    private static final int[][] CASES = {
            {0, Kit.JUMPER.ordinal()},
            {0, Kit.REPULSOR.ordinal()},
            {1, Kit.SLOWBALLER.ordinal()},
            {1, Kit.BODY_BUILDER.ordinal()},
            {2, Kit.REPULSOR.ordinal()},
            {2, Kit.MAVERICK.ordinal()}
    };

    @Test
    void sweepJumperIqGapRiskOnRepresentativeSpeedCases() {
        double[] iqValues = {0.25, 0.50, 0.70, 0.90, 1.00};
        List<Row> rows = new ArrayList<>();

        for (double iq : iqValues) {
            AiProfile profile = new AiProfile(
                    new AiAttributes(1.0, 0.85, 0.85, 0.90),
                    new AiTendencies(
                            AiTendencies.DirectionChangeType.MIXED,
                            0.70, 0.55, iq, 0.85, 0.80, 0.80));

            int total = 0, stage10 = 0, stage15 = 0;
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;

            for (int[] testCase : CASES) {
                Kit kit = Kit.values()[testCase[1]];
                AuthenticStage10SimulationTest.RunResult result =
                        AuthenticStage10SimulationTest.runToEnd(
                                testCase[0], kit, profile, Mode.SPEED);
                total += result.maxStage();
                if (result.maxStage() >= 10) stage10++;
                if (result.maxStage() >= 15) stage15++;
                min = Math.min(min, result.maxStage());
                max = Math.max(max, result.maxStage());

                System.out.printf(
                        "SPEED_GAP_RISK iq=%.2f pattern=%d kit=%s stage=%d ticks=%d hp=%s fallTick=%d%n",
                        iq, testCase[0] + 1, kit, result.maxStage(), result.ticks(),
                        Double.toString(result.health()), result.firstFallTick());
            }

            rows.add(new Row(iq, total / (double) CASES.length,
                    stage10, stage15, min, max));
        }

        rows.sort(Comparator.comparingDouble(Row::average).reversed()
                .thenComparingInt(Row::stage15).reversed()
                .thenComparingInt(Row::stage10).reversed());

        System.out.println("===== SPEED GAP-RISK SWEEP SUMMARY =====");
        for (Row row : rows) {
            System.out.printf(
                    "jumperIq=%.2f average=%.3f stage10=%d/6 stage15=%d/6 min=%d max=%d%n",
                    row.iq(), row.average(), row.stage10(), row.stage15(),
                    row.minStage(), row.maxStage());
        }
    }

    private record Row(double iq, double average, int stage10, int stage15,
                       int minStage, int maxStage) {}
}
