package me.monstermazeai.physics;

import me.monstermazeai.game.GameState;
import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.player.Action;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MovementBenchmarkTest {
    private static GameState player(){
        GameState s=new GameState(); s.mode=Mode.MODERN;s.maze=openMaze();s.kit=Kit.JUMPER;
        s.player.grounded=true;s.player.y=GameState.PATH_Y;s.ability.charges=100;s.player.jumpCharges=100;return s;
    }
    private static MazeModel openMaze(){
        int[][] raw=new int[MazeModel.SIZE][MazeModel.SIZE];
        for(int r=0;r<MazeModel.SIZE;r++)for(int c=0;c<MazeModel.SIZE;c++)raw[r][c]=1;
        return new MazeModel(raw);
    }
    private static double run(int ticks,int jumpEvery){
        GameState s=player(); LegacyMovementModel physics=new LegacyMovementModel();
        for(int t=0;t<ticks;t++){
            boolean jump=jumpEvery>0 && t%jumpEvery==0;
            physics.tick(s.player,new Action(1,0,jump,true,0,false));
        }
        return Math.hypot(s.player.x,s.player.z);
    }
    @Test void sprintAndJumpPatternsProduceMeasurableTrajectories(){
        double sprint=run(60,0),best=sprint; assertTrue(sprint>0);
        for(int interval=4;interval<=12;interval++){double d=run(60,interval);assertTrue(d>0);best=Math.max(best,d);}
        assertTrue(best>sprint*1.05);
    }
    @Test void movementIsTickDeterministic(){
        assertEquals(run(60,6),run(60,6),1e-12);
        assertEquals(run(60,8),run(60,8),1e-12);
    }
}
