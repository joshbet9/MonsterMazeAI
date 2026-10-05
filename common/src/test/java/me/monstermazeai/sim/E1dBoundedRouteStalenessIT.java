package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import org.junit.jupiter.api.Test;

class E1dBoundedRouteStalenessIT {
    @Test
    void difficultCells() {
        Object[][] specs = {
                {Mode.MODERN,2,Kit.MAVERICK,5},
                {Mode.MODERN,2,Kit.JUMPER,10},
                {Mode.MODERN,1,Kit.BODY_BUILDER,31},
                {Mode.MODERN,0,Kit.MAVERICK,11},
                {Mode.SPEED,1,Kit.MAVERICK,96},
                {Mode.SPEED,2,Kit.BODY_BUILDER,36}
        };
        double sum=0;
        for(Object[] s: specs){
            Mode mode=(Mode)s[0]; int pattern=(Integer)s[1]; Kit kit=(Kit)s[2]; int baseline=(Integer)s[3];
            AuthenticStage10SimulationTest.RunResult r=AuthenticStage10SimulationTest.run(
                    pattern,kit,AiProfile.HIGH_SKILL,mode,0,true);
            sum+=r.maxStage();
            System.out.printf("STALE4_MATRIX mode=%s pattern=%d kit=%s stage=%d baseline=%d ticks=%d%n",
                    mode,pattern+1,kit,r.maxStage(),baseline,r.ticks());
        }
        System.out.printf("STALE4_MEAN=%.4f%n",sum/specs.length);
    }
}
