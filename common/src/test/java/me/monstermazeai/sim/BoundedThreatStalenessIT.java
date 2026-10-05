package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import org.junit.jupiter.api.Test;

class BoundedThreatStalenessIT {
    @Test
    void modernNaturalEndTenCells() {
        int[][] cases = {
                {1, Kit.JUMPER.ordinal()},
                {1, Kit.SLOWBALLER.ordinal()},
                {1, Kit.BODY_BUILDER.ordinal()},
                {1, Kit.REPULSOR.ordinal()},
                {1, Kit.MAVERICK.ordinal()},
                {2, Kit.JUMPER.ordinal()},
                {2, Kit.SLOWBALLER.ordinal()},
                {2, Kit.BODY_BUILDER.ordinal()},
                {2, Kit.REPULSOR.ordinal()},
                {2, Kit.MAVERICK.ordinal()}
        };
        double sum = 0.0;
        for (int[] spec : cases) {
            int pattern = spec[0] - 1;
            Kit kit = Kit.values()[spec[1]];
            AuthenticStage10SimulationTest.RunResult r =
                    AuthenticStage10SimulationTest.run(
                            pattern, kit, AiProfile.HIGH_SKILL, Mode.MODERN, true);
            sum += r.maxStage();
            System.out.printf(
                    "STALE_MATRIX mode=MODERN pattern=%d kit=%s stage=%d ticks=%d health=%.2f%n",
                    pattern + 1, kit, r.maxStage(), r.ticks(), r.health());
        }
        System.out.printf("STALE_MODERN_MEAN=%.4f%n", sum / cases.length);
    }
}
