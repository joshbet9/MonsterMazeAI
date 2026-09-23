package me.monstermazeai.sim;

import me.monstermazeai.game.GameState;
import me.monstermazeai.player.Action;

public final class TrajectoryRiskEstimator {
    public record Result(int samples,int survived,int padReached,int damaged,
                         double survivalProbability,double padProbability,double damageProbability,
                         double meanHealthRemaining,double meanPadDistance) {}
    private final Simulator simulator;
    public TrajectoryRiskEstimator(Simulator simulator){this.simulator=simulator;}

    public Result evaluate(GameState source,Action[] actions,long[] seeds){
        if(seeds.length==0) throw new IllegalArgumentException("At least one seed is required.");
        int survived=0,padReached=0,damaged=0; double health=0,distance=0;
        for(long seed:seeds){
            GameState result=simulator.forecast(source,actions,seed);
            if(result.alive) survived++;
            if(result.padReached) padReached++;
            if(result.player.health<source.player.health) damaged++;
            health+=result.player.health;
            double tx=result.targetPadX(),tz=result.targetPadZ();
            distance+=(Double.isNaN(tx)||Double.isNaN(tz))?0:Math.hypot(result.player.x-tx,result.player.z-tz);
        }
        int n=seeds.length;
        return new Result(n,survived,padReached,damaged,survived/(double)n,padReached/(double)n,
                damaged/(double)n,health/n,distance/n);
    }

    public static long[] seeds(long base,int count){
        if(count<1) throw new IllegalArgumentException("count must be positive");
        long[] result=new long[count]; long x=base;
        for(int i=0;i<count;i++){
            x+=0x9E3779B97F4A7C15L;
            x=(x^(x>>>30))*0xBF58476D1CE4E5B9L;
            x=(x^(x>>>27))*0x94D049BB133111EBL;
            result[i]=x^(x>>>31);
        }
        return result;
    }
}
