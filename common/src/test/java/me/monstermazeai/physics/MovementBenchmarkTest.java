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
    void nonJumperSpeedingUsesHorizontalSprintJumpImpulseAcrossOneBlockGap() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        raw[10][10] = 1;
        raw[10][12] = 1;
        MazeModel maze = new MazeModel(raw);

        GameState s = player();
        s.kit = Kit.MAVERICK;
        s.player.x = 10.5;
        s.player.z = 10.99;
        s.player.y = GameState.PATH_Y;
        s.player.yaw = 0.0F;
        s.player.grounded = true;
        s.player.vz = 0.39;

        LegacyMovementModel physics = new LegacyMovementModel();
        physics.tick(s.player, new Action(1, 0, true, true, 0, false), maze, -10);

        assertEquals(GameState.PATH_Y, s.player.y, 1.0e-9,
                "Jump -10 must suppress vertical lift for non-Jumper speeding");
        assertTrue(s.player.z > 11.70,
                "the source sprint-jump horizontal impulse must carry the player AABB onto the destination side");
        assertTrue(s.player.grounded,
                "a successful speeding gap crossing must retain physical support on the destination block");
        assertTrue(s.player.vz > 0.0,
                "the successful crossing must preserve forward momentum");
    }

    @Test
    void nonJumperHeldJumpUsesOneImpulsePerJumpPress() {
        GameState s = player();
        s.kit = Kit.MAVERICK;
        s.player.yaw = 0.0F;
        s.player.vz = 0.30;

        LegacyMovementModel physics = new LegacyMovementModel();
        Action jump = new Action(1, 0, true, true, 0, false);

        physics.tick(s.player, jump, s.maze, -10);
        double firstVz = s.player.vz;
        physics.tick(s.player, jump, s.maze, -10);
        double secondVz = s.player.vz;

        assertTrue(firstVz > secondVz,
                "the first non-Jumper jump press adds the sprint-jump impulse");
        assertEquals(9, s.player.jumpTicks,
                "holding jump must enter the source-style jump cooldown");
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
