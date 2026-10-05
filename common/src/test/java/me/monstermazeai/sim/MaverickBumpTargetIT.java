package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import org.junit.jupiter.api.Test;

class MaverickBumpTargetIT {
    @Test
    void allMaverickCells() {
        Object[][] specs = {
                {Mode.SPEED,0,26},{Mode.SPEED,1,96},{Mode.SPEED,2,29},
                {Mode.MODERN,0,11},{Mode.MODERN,1,19},{Mode.MODERN,2,5}
        };
        double sum=0;
        for(Object[] s: specs){
            Mode mode=(Mode)s[0]; int pattern=(Integer)s[1]; int baseline=(Integer)s[2];
            AuthenticStage10SimulationTest.RunResult r=AuthenticStage10SimulationTest.run(
                    pattern, Kit.MAVERICK, AiProfile.HIGH_SKILL, mode,0,true);
            sum += r.maxStage();
            System.out.printf("MAVBUMP_MATRIX mode=%s pattern=%d stage=%d baseline=%d ticks=%d%n",
                    mode,pattern+1,r.maxStage(),baseline,r.ticks());
        }
        System.out.printf("MAVBUMP_MEAN=%.4f%n",sum/specs.length);
    }
}
