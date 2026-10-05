package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import org.junit.jupiter.api.Test;

class AdaptiveThreatHorizonV2IT {
    @Test
    void mixedCells() {
        Object[][] specs = {
                {Mode.SPEED,0,Kit.BODY_BUILDER,32},
                {Mode.SPEED,1,Kit.MAVERICK,96},
                {Mode.SPEED,2,Kit.SLOWBALLER,27},
                {Mode.SPEED,2,Kit.REPULSOR,22},
                {Mode.MODERN,0,Kit.MAVERICK,11},
                {Mode.MODERN,1,Kit.BODY_BUILDER,31},
                {Mode.MODERN,1,Kit.MAVERICK,23},
                {Mode.MODERN,2,Kit.MAVERICK,5},
                {Mode.MODERN,2,Kit.REPULSOR,13},
                {Mode.MODERN,2,Kit.JUMPER,10},
                {Mode.SPEED,0,Kit.SLOWBALLER,27},
                {Mode.MODERN,0,Kit.SLOWBALLER,11}
        };
        double sum=0;
        for(Object[] s:specs){
            Mode mode=(Mode)s[0]; int p=(Integer)s[1]; Kit kit=(Kit)s[2]; int baseline=(Integer)s[3];
            var r=AuthenticStage10SimulationTest.run(p,kit,AiProfile.HIGH_SKILL,mode,0,true);
            sum += r.maxStage();
            System.out.printf("ADAPTV2_MATRIX mode=%s pattern=%d kit=%s stage=%d baseline=%d ticks=%d%n",
                    mode,p+1,kit,r.maxStage(),baseline,r.ticks());
        }
        System.out.printf("ADAPTV2_MEAN=%.4f%n",sum/specs.length);
    }
}