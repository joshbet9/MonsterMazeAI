package me.monstermazeai.sim;

import me.monstermazeai.collision.CollisionModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.player.Action;
import me.monstermazeai.physics.LegacyMazePhysics;
import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class TrajectoryRiskEstimatorTest {
    private static MazeModel openMaze(){
        int[][] raw=new int[MazeModel.SIZE][MazeModel.SIZE];
        for(int r=0;r<MazeModel.SIZE;r++)for(int c=0;c<MazeModel.SIZE;c++)raw[r][c]=1;
        return new MazeModel(raw);
    }
    @Test void independentSeedsProduceAStableAggregate(){
        GameState s=new GameState();s.mode=Mode.MODERN;s.maze=openMaze();s.kit=Kit.JUMPER;s.phaseTicksRemaining=300;
        s.activePadRow=90;s.activePadColumn=90;s.player.x=10.5;s.player.z=20.5;s.player.grounded=true;s.player.y=GameState.PATH_Y;
        s.ability.charges=3;s.player.jumpCharges=3;s.monsters.add(new MonsterState(1,12,0,20.5));
        Simulator simulator=new Simulator(new LegacyMazePhysics(),new MonsterSimulator(s.maze,new Random(1234),0.07),new CollisionModel());
        Action[] actions=new Action[40];java.util.Arrays.fill(actions,new Action(1,0,false,true,0,false));
        var result=new TrajectoryRiskEstimator(simulator).evaluate(s,actions,TrajectoryRiskEstimator.seeds(42L,100));
        assertEquals(100,result.samples());assertTrue(result.survivalProbability()>=0&&result.survivalProbability()<=1);
        assertTrue(result.damageProbability()>=0&&result.damageProbability()<=1);assertTrue(result.meanHealthRemaining()<=s.player.health);
    }
}
