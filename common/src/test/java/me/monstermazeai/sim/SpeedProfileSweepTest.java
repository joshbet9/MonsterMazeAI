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

/**
 * Evidence-driven Speed-mode profile tuning.
 *
 * The sweep changes only AI profile values. Authoritative Minecraft physics,
 * maze geometry, monster simulation and kit mechanics remain untouched.
 */
class SpeedProfileSweepTest {
    private static final int[][] CASES = {
            {0, Kit.JUMPER.ordinal()},
            {0, Kit.SLOWBALLER.ordinal()},
            {0, Kit.BODY_BUILDER.ordinal()},
            {1, Kit.JUMPER.ordinal()},
            {1, Kit.SLOWBALLER.ordinal()},
            {1, Kit.BODY_BUILDER.ordinal()},
            {2, Kit.JUMPER.ordinal()},
            {2, Kit.SLOWBALLER.ordinal()},
            {2, Kit.BODY_BUILDER.ordinal()}
    };

    @Test
    void sweepHandlingOnAllSpeedCases() {
        double[] handlingValues = {0.70, 0.80, 0.90, 0.95, 0.99};
        List<Row> rows = new ArrayList<>();

        for (double handling : handlingValues) {
            AiProfile profile = profile(handling);
            int totalStage = 0;
            int stage10 = 0;
            int stage15 = 0;
            int minStage = Integer.MAX_VALUE;
            int maxStage = Integer.MIN_VALUE;

            for (int pattern = 0; pattern < CASES.length; pattern++) {
                for (Kit kit : CASES[pattern]) {
                    AuthenticStage10SimulationTest.RunResult result =
                            AuthenticStage10SimulationTest.runDiagnostic(
                                    pattern, kit, profile, Mode.SPEED, 20);
                    totalStage += result.maxStage();
                    if (result.maxStage() >= 10) stage10++;
                    if (result.maxStage() >= 15) stage15++;
                    minStage = Math.min(minStage, result.maxStage());
                    maxStage = Math.max(maxStage, result.maxStage());

                    System.out.printf(
                            "SPEED_PROFILE handling=%.2f pattern=%d kit=%s stage=%d ticks=%d hp=%s fallTick=%d%n",
                            handling, pattern + 1, kit, result.maxStage(), result.ticks(),
                            Double.toString(result.health()), result.firstFallTick());
                }
            }

            double average = totalStage / (double) CASES.length;
            rows.add(new Row(handling, average, stage10, stage15, minStage, maxStage));
        }

        rows.sort(Comparator.comparingDouble(Row::average).reversed()
                .thenComparingInt(Row::stage15).reversed()
                .thenComparingInt(Row::stage10).reversed()
                .thenComparingInt(Row::minStage).reversed());

        System.out.println("===== SPEED PROFILE SWEEP SUMMARY =====");
        for (Row row : rows) {
            System.out.printf(
                    "handling=%.2f average=%.3f stage10=%d/%d stage15=%d/%d min=%d max=%d%n",
                    row.handling(), row.average(), row.stage10(), CASES.length, row.stage15(), CASES.length,
                    row.minStage(), row.maxStage());
        }
    }

    private static AiProfile profile(double handling) {
        return new AiProfile(
                new AiAttributes(1.0, 0.85, handling, 0.90),
                new AiTendencies(
                        AiTendencies.DirectionChangeType.MIXED,
                        0.70, 0.55, 0.90, 0.85, 0.80, 0.80));
    }

    private record Row(double handling, double average, int stage10, int stage15,
                       int minStage, int maxStage) {}
}
