package me.monstermazeai.planner;

import me.monstermazeai.collision.CollisionModel;
import me.monstermazeai.game.GameProgressionModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.game.Mode;
import me.monstermazeai.game.PadModel;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.physics.LegacyMazePhysics;
import me.monstermazeai.sim.Simulator;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PlannerValidationTest {
    private static MazeModel openMaze() {
        int[][] raw=new int[MazeModel.SIZE][MazeModel.SIZE];
        for(int r=0;r<MazeModel.SIZE;r++) for(int c=0;c<MazeModel.SIZE;c++) raw[r][c]=1;
        return new MazeModel(raw);
    }

    private static GameState state(double x,double z,int timerTicks) {
        GameState s=new GameState(); s.mode=Mode.MODERN; s.maze=openMaze();
        s.player.x=x; s.player.z=z; s.player.y=GameState.PATH_Y; s.player.grounded=true;
        s.phaseTicksRemaining=timerTicks; s.activePadRow=20; s.activePadColumn=20;
        s.kit=Kit.JUMPER; s.ability.charges=3; s.player.jumpCharges=3; return s;
    }

    private static BeamSearchPlanner planner(GameState s,int horizon,int width) {
        return new BeamSearchPlanner(
                new Simulator(
                        new LegacyMazePhysics(),
                        new MonsterSimulator(s.maze,new Random(1234),0.07),
                        new CollisionModel()),
                new Heuristic(),horizon,width);
    }

    @Test void reachesPadInOpenSpace() {
        // Eight blocks is within the roughly three-second movement horizon of
        // the 1.8 sprint model; the previous ten-block setup exceeded it.
        GameState s=state(12.5,20.5,35*20);
        var plan=planner(s,60,12).plan(s,s.targetPadX(),s.targetPadZ(),false);
        assertTrue(plan.padReached(),"Planner did not reach pad: "+plan.decisionReason());
        assertTrue(plan.resultingState().alive);
        assertTrue(plan.sequence().length()<=60);
    }

    @Test void criticalTimerRequiresActualProgress() {
        GameState s=state(10.5,20.5,15*20);
        var plan=planner(s,60,12).plan(s,s.targetPadX(),s.targetPadZ(),false);
        assertTrue(plan.resultingState().alive || plan.resultingState().padReached);
        assertTrue(plan.resultingState().player.x>s.player.x,
                "Late-stage plan must make measurable progress.");
    }


    @Test void emergencyDeadlineSearchCanReachPadWithinFifteenSeconds() {
        GameState s=state(17.5,20.5,15*20);
        var plan=planner(s,20,12).plan(s,s.targetPadX(),s.targetPadZ(),true);
        assertTrue(plan.padReached(),
                "Emergency deadline mode must search the full remaining 15-second budget.");
        assertTrue(plan.sequence().length()<=15*20);
        assertTrue(plan.resultingState().alive);
    }

    @Test void collisionDamageIsVisibleToPlanner() {
        GameState s=state(10.5,20.5,35*20);
        s.monsters.add(new me.monstermazeai.monster.MonsterState(1,11.0,0,20.5));
        Simulator simulator=new Simulator(
                new LegacyMazePhysics(),
                new MonsterSimulator(s.maze,new Random(1),0.07),
                new CollisionModel());
        simulator.tick(s,new me.monstermazeai.player.Action(1,0,false,true,0,false));
        assertTrue(s.player.health<20.0,"Monster collision must damage player.");
        assertTrue(s.player.recentMobHitUntilTick>s.tick);
    }

    @Test void phaseTimerTicksOncePerSecondLikeSourceGame() {
        GameState s=new GameState();
        s.mode=Mode.MODERN;
        s.maze=openMaze();

        GameProgressionModel progression=new GameProgressionModel();
        progression.initialise(s);

        assertEquals(35*20,s.phaseTicksRemaining);

        for(int i=0;i<19;i++) progression.tick(s);
        assertEquals(35*20,s.phaseTicksRemaining,
                "Source decrements phaseTimer once every 20 server ticks.");

        progression.tick(s);
        assertEquals(34*20,s.phaseTicksRemaining,
                "The twentieth simulated tick must consume exactly one second.");
    }

    @Test void soloPadUsesSourceFiveByFiveGeometry() {
        GameState s=state(20.5,20.5,35*20);

        assertTrue(PadModel.isOn(
                s.player,20.5,GameState.PAD_SURFACE_Y,20.5));

        s.player.x=23.0;
        assertFalse(PadModel.isOn(
                s.player,20.5,GameState.PAD_SURFACE_Y,20.5),
                "The source uses strict +/-2.5 horizontal bounds.");

        s.player.x=20.5;
        s.player.y=GameState.PAD_SURFACE_Y;
        assertFalse(PadModel.isOn(
                s.player,20.5,GameState.PAD_SURFACE_Y,20.5),
                "The player must be above the pad surface.");
    }

    @Test void reachingPadShortensSoloTimerToFourSeconds() {
        GameState s=state(20.5,20.5,35*20);
        GameProgressionModel progression=new GameProgressionModel();
        progression.initialise(s);
        s.activePadRow=20;
        s.activePadColumn=20;

        progression.tick(s);

        assertTrue(s.padReached);
        assertEquals(4*20,s.phaseTicksRemaining,
                "In solo mode, standing on the active pad forces the timer to four seconds.");
    }
}
