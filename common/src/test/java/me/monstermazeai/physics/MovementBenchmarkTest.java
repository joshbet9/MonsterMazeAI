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
        s.mode = Mode.SPEED;
        s.kit = Kit.MAVERICK;
        s.player.x = 10.5;
        s.player.z = 11.05;
        s.player.y = GameState.PATH_Y;
        s.player.yaw = 0.0F;
        s.player.grounded = true;
        // Start on the supported lip of the source block so the source
        // sprint-jump horizontal impulse can carry the AABB into the destination.
        s.player.vz = 0.42;

        LegacyMovementModel physics = new LegacyMovementModel();

        // The source sprint-jump horizontal impulse is applied before the
        // normal ground-friction step. Starting at z=10.95 therefore cannot
        // overlap the destination AABB in a single tick; the faithful model
        // reaches it on the following airborne tick.
        physics.tick(s.player, new Action(1, 0, true, true, 0, false), maze, -10);

        assertEquals(GameState.PATH_Y, s.player.y, 1.0e-9,
                "Jump -10 must suppress vertical lift for non-Jumper speeding");
        assertTrue(s.player.z > 11.5,
                "the first Speed pulse must carry the player to the far lip of the source gap");

        boolean landed = false;
        for (int i = 0; i < 6; i++) {
            if (s.player.grounded && s.player.z > 12.0) {
                landed = true;
                break;
            }
            physics.tick(
                    s.player,
                    new Action(1, 0, i < 2, true, 0, false),
                    maze,
                    -10);
        }

        assertTrue(landed,
                "the source sprint-jump trajectory must eventually regain support on the destination side");
        assertEquals(GameState.PATH_Y, s.player.y, 1.0e-9,
                "the landing tick must restore the path height");
        assertTrue(s.player.z > 11.70,
                "the source sprint-jump horizontal impulse must carry the player AABB onto the destination side");
        assertTrue(s.player.vz > 0.0,
                "the successful crossing must preserve forward momentum");
    }

    @Test void movementIsTickDeterministic(){
        assertEquals(run(60,6),run(60,6),1e-12);
        assertEquals(run(60,8),run(60,8),1e-12);
    }
}
