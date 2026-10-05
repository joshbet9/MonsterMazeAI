package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import org.junit.jupiter.api.Test;

class NoMobNaturalMatrixMainIT {
    @Test
    void fullNoMobNaturalEndMatrix() {
        double speedSum = 0.0;
        double modernSum = 0.0;
        int speedCount = 0;
        int modernCount = 0;

        for (Mode mode : new Mode[]{Mode.SPEED, Mode.MODERN}) {
            for (int pattern = 0; pattern < 3; pattern++) {
                for (Kit kit : Kit.values()) {
                    AuthenticStage10SimulationTest.RunResult r =
                            AuthenticStage10SimulationTest.run(
                                    pattern, kit, AiProfile.HIGH_SKILL,
                                    mode, true, false);
                    if (mode == Mode.SPEED) {
                        speedSum += r.maxStage();
                        speedCount++;
                    } else {
                        modernSum += r.maxStage();
                        modernCount++;
                    }
                    System.out.printf(
                            "NO_MOB_MATRIX mode=%s pattern=%d kit=%s stage=%d ticks=%d health=%.2f%n",
                            mode, pattern + 1, kit, r.maxStage(), r.ticks(), r.health());
                }
            }
        }

        System.out.printf("NO_MOB_SPEED_MEAN=%.4f%n", speedSum / speedCount);
        System.out.printf("NO_MOB_MODERN_MEAN=%.4f%n", modernSum / modernCount);
    }
}
