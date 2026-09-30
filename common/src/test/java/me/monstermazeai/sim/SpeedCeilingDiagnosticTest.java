package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import org.junit.jupiter.api.Test;

/** Measures the current Speed ceiling without changing acceptance thresholds. */
class SpeedCeilingDiagnosticTest {
    @Test
    void allSpeedCasesToStageFifteen() {
        for (int pattern = 0; pattern < 3; pattern++) {
            for (Kit kit : Kit.values()) {
                AuthenticStage10SimulationTest.RunResult result =
                        AuthenticStage10SimulationTest.runDiagnostic(
                                pattern, kit, AiProfile.HIGH_SKILL, Mode.SPEED, 15);
                System.out.printf(
                        "SPEED_CEILING pattern=%d kit=%s stage=%d tick=%d hp=%s fallTick=%d%n",
                        pattern + 1, kit, result.maxStage(), result.ticks(),
                        Double.toString(result.health()), result.firstFallTick());
            }
        }
    }
}
