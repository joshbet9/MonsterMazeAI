package me.monstermazeai.ability;
import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.PlayerPathfinder;
import me.monstermazeai.monster.MonsterState;
import java.util.List;

public final class AbilityDecision {
    private static final double IMMEDIATE=2.5;
    private static final double DANGER=3.5;
    private static final double SPEED=0.115;
    private static final int MAX_TICKS=200;
    private AbilityDecision(){}

    public static boolean shouldUse(GameState s){
        if(s==null||!s.alive||s.completed||s.maze==null||s.kit==Kit.JUMPER||s.kit==Kit.MAVERICK)return false;
        if(s.kit==Kit.REPULSOR&&s.ability.charges<=0)return false;
        if(s.kit==Kit.BODY_BUILDER&&s.ability.activations<=0)return false;
        if(s.kit==Kit.SLOWBALLER&&s.tick<s.ability.cooldownUntilTick)return false;
        double nearest=nearestMonster(s);
        if(Double.isInfinite(nearest))return false;
        boolean immediate=nearest<=IMMEDIATE;
        boolean lowHealth=s.player.health<=4.0&&nearest<=DANGER;
        int travel=travelTicks(s);
        boolean deadline=s.phaseTicksRemaining>0 && travel>s.phaseTicksRemaining-10;
        if(s.stage<=1&&s.kit!=Kit.SLOWBALLER&&!immediate&&!lowHealth&&!deadline)return false;
        return immediate||lowHealth||deadline||(s.kit==Kit.SLOWBALLER&&nearest<=4.0);
    }

    private static double nearestMonster(GameState s){
        double best=Double.POSITIVE_INFINITY;
        for(MonsterState m:s.monsters){
            if(m.removed||m.launched(s.tick)||m.frozen(s.tick))continue;
            best=Math.min(best,Math.hypot(s.player.x-m.x,s.player.z-m.z));
        }
        return best;
    }
    private static int travelTicks(GameState s){
        if(s.activePadRow<0||s.activePadColumn<0)return MAX_TICKS;
        int r=(int)Math.floor(s.player.x),c=(int)Math.floor(s.player.z);
        List<Cell> path=new PlayerPathfinder().shortestPath(s.maze,new Cell(r,c),new Cell(s.activePadRow,s.activePadColumn));
        if(path.isEmpty())return MAX_TICKS;
        return Math.min(MAX_TICKS,(int)Math.ceil(Math.max(0,path.size()-1)/SPEED));
    }
}
