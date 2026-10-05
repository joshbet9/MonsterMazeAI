package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import org.junit.jupiter.api.Test;

class MaverickStallBumpIT {
    @Test
    void allMaverickCells() {
        for (Mode mode : new Mode[]{Mode.SPEED, Mode.MODERN}) {
            for (int pattern = 0; pattern < 3; pattern++) {
                var r = AuthenticStage10SimulationTest.run(
                        pattern, Kit.MAVERICK, AiProfile.HIGH_SKILL, mode, true);
                System.out.printf(
                        "STALL_BUMP mode=%s pattern=%d stage=%d ticks=%d health=%.2f%n",
                        mode, pattern + 1, r.maxStage(), r.ticks(), r.health());
            }
        }
    }
}
