package me.monstermazeai.sim;

import me.monstermazeai.collision.CollisionModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.physics.LegacyMazePhysics;
import me.monstermazeai.planner.BeamSearchPlanner;
import me.monstermazeai.planner.Heuristic;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PlanMonteCarloBenchmarkTest {
    private static MazeModel openMaze(){
        int[][] raw=new int[MazeModel.SIZE][MazeModel.SIZE];
        for(int r=0;r<MazeModel.SIZE;r++)for(int c=0;c<MazeModel.SIZE;c++)raw[r][c]=1;
        return new MazeModel(raw);
    }

    @Test void plannedTrajectoryCanBeEvaluatedAcrossOneThousandFutures(){
        GameState s=new GameState();
        s.mode=Mode.MODERN;s.maze=openMaze();s.kit=Kit.JUMPER;s.phaseTicksRemaining=700;
        s.activePadRow=20;s.activePadColumn=20;
        s.player.x=12.5;s.player.z=20.5;s.player.y=GameState.PATH_Y;s.player.grounded=true;
        s.ability.charges=3;s.player.jumpCharges=3;
        s.monsters.add(new MonsterState(1,16,0,20.5));
        s.monsters.add(new MonsterState(2,18,0,21.5));
        s.monsters.add(new MonsterState(3,17,0,19.5));

        Simulator simulator=new Simulator(new LegacyMazePhysics(),
                new MonsterSimulator(s.maze,new Random(1234),0.07),new CollisionModel());
        BeamSearchPlanner planner=new BeamSearchPlanner(simulator,new Heuristic(),40,12);
        var plan=planner.plan(s,s.targetPadX(),s.targetPadZ(),true);

        var result=new TrajectoryRiskEstimator(simulator).evaluate(
                s,plan.sequence().actions(),TrajectoryRiskEstimator.seeds(20260923L,1000));

        assertEquals(1000,result.samples());
        assertTrue(result.survivalProbability()>=0.0 && result.survivalProbability()<=1.0);
        assertTrue(result.padProbability()>=0.0 && result.padProbability()<=1.0);
        assertTrue(result.damageProbability()>=0.0 && result.damageProbability()<=1.0);
        assertTrue(result.meanHealthRemaining()>=0.0);
    }
}
