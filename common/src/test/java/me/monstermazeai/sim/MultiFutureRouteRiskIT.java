package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import org.junit.jupiter.api.Test;

class MultiFutureRouteRiskIT {
    @Test
    void difficultCells() {
        Object[][] specs = {
                {Mode.MODERN,0,Kit.MAVERICK},
                {Mode.MODERN,1,Kit.BODY_BUILDER},
                {Mode.MODERN,2,Kit.MAVERICK},
                {Mode.MODERN,2,Kit.REPULSOR},
                {Mode.SPEED,1,Kit.MAVERICK},
                {Mode.SPEED,2,Kit.BODY_BUILDER}
        };
        double sum=0;
        for(Object[] s: specs){
            Mode mode=(Mode)s[0]; int pattern=(Integer)s[1]; Kit kit=(Kit)s[2];
            AuthenticStage10SimulationTest.RunResult r=AuthenticStage10SimulationTest.run(
                    pattern,kit,AiProfile.HIGH_SKILL,mode,0,true);
            sum += r.maxStage();
            System.out.printf("ROBUST_MATRIX mode=%s pattern=%d kit=%s stage=%d ticks=%d%n",
                    mode,pattern+1,kit,r.maxStage(),r.ticks());
        }
        System.out.printf("ROBUST_MEAN=%.4f%n",sum/specs.length);
    }
}
