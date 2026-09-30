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
    
    @Test
    void nonJumperJumpRequestDoesNotInjectSyntheticSprintJumpImpulse() {
        GameState jumping = player();
        GameState walking = player();

        jumping.kit = Kit.MAVERICK;
        walking.kit = Kit.MAVERICK;
        jumping.player.yaw = 37.0F;
        walking.player.yaw = 37.0F;
        jumping.player.vx = 0.24;
        walking.player.vx = 0.24;
        jumping.player.vz = 0.31;
        walking.player.vz = 0.31;

        LegacyMovementModel physics = new LegacyMovementModel();
        Action jump = new Action(1, 0, true, true, 0, false);
        Action noJump = new Action(1, 0, false, true, 0, false);

        physics.tick(jumping.player, jump, jumping.maze, -10);
        physics.tick(walking.player, noJump, walking.maze, -10);

        assertEquals(walking.player.x, jumping.player.x, 1.0e-12,
                "Jump -10 must not add horizontal sprint-jump displacement");
        assertEquals(walking.player.z, jumping.player.z, 1.0e-12,
                "Jump -10 must not add horizontal sprint-jump displacement");
        assertEquals(walking.player.vx, jumping.player.vx, 1.0e-12,
                "Jump -10 must not add horizontal sprint-jump velocity");
        assertEquals(walking.player.vz, jumping.player.vz, 1.0e-12,
                "Jump -10 must not add horizontal sprint-jump velocity");
        assertEquals(-0.0784000015258789D, jumping.player.vy, 1.0e-12,
                "Grounded 1.8 motionY is retained after a suppressed jump request");
        assertTrue(jumping.player.grounded);
    }

    @Test
    void groundedMotionYMatchesMinecraft18Telemetry() {
        GameState s = player();
        s.player.y = GameState.PATH_Y;
        s.player.grounded = true;

        new LegacyMovementModel().tick(s.player, Action.IDLE, s.maze, -10);

        assertEquals(GameState.PATH_Y, s.player.y, 1.0e-12);
        assertEquals(-0.0784000015258789D, s.player.vy, 1.0e-12);
        assertTrue(s.player.grounded);
    }

    @Test void movementIsTickDeterministic(){
        assertEquals(run(60,6),run(60,6),1e-12);
        assertEquals(run(60,8),run(60,8),1e-12);
    }
}
