package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import org.junit.jupiter.api.Test;

class NaturalEndMatrixMainBaselineIT {

    @Test
    void fullNaturalEndMatrix() {
        for (Mode mode : new Mode[]{Mode.SPEED, Mode.MODERN}) {
            for (int pattern = 0; pattern < 3; pattern++) {
                for (Kit kit : Kit.values()) {
                    AuthenticStage10SimulationTest.RunResult r =
                            AuthenticStage10SimulationTest.run(
                                    pattern, kit, AiProfile.HIGH_SKILL, mode, true);
                    System.out.printf(
                            "FULL_MATRIX mode=%s pattern=%d kit=%s stage=%d ticks=%d health=%.2f%n",
                            mode, pattern + 1, kit, r.maxStage(), r.ticks(), r.health());
                }
            }
        }
    }
}
