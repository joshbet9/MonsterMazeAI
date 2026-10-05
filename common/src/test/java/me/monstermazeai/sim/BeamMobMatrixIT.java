package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import org.junit.jupiter.api.Test;

class BeamMobMatrixIT {
    @Test
    void representativeModernCells() {
        int[] patterns = {0, 1, 2};
        Kit[] kits = {Kit.SLOWBALLER, Kit.BODY_BUILDER, Kit.REPULSOR, Kit.MAVERICK};
        double sum = 0.0;
        int count = 0;
        for (int pattern : patterns) {
            for (Kit kit : kits) {
                AuthenticStage10SimulationTest.RunResult r =
                        AuthenticStage10SimulationTest.run(
                                pattern, kit, AiProfile.HIGH_SKILL,
                                Mode.MODERN, true,
                                AuthenticStage10SimulationTest.ControllerMode.BEAM_MOB);
                sum += r.maxStage();
                count++;
                System.out.printf(
                        "BEAM_MATRIX mode=MODERN pattern=%d kit=%s stage=%d ticks=%d health=%.2f%n",
                        pattern + 1, kit, r.maxStage(), r.ticks(), r.health());
            }
        }
        System.out.printf("BEAM_MODERN_MEAN=%.4f%n", sum / count);
    }
}
