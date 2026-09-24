package me.monstermazeai.runtime;

import me.monstermazeai.game.GameState;
import me.monstermazeai.player.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerSpecificNpcAgentTest {
    @Test void stylesAutonomousActionWithoutReplacingObjective() {
        AutonomousMonsterMazeAgent base = new AutonomousMonsterMazeAgent(
                new me.monstermazeai.planner.RobustLiveController(
                        new me.monstermazeai.planner.LiveObjectiveController(
                                new me.monstermazeai.planner.MazeAwareRecedingHorizonController(
                                        new me.monstermazeai.planner.BeamSearchPlanner()))));
        PlayerBehaviorProfile p = new PlayerBehaviorProfile(10,0.2,1,0,0,0,0,0,1,0);
        PlayerSpecificNpcAgent agent = new PlayerSpecificNpcAgent(base,new PlayerSpecificNpcModel(p));
        GameState state = new GameState();
        state.inMonsterMaze=true; state.alive=true; state.completed=false;
        state.mazePattern=1; state.phaseTicksRemaining=100;
        state.maze=new me.monstermazeai.maze.MazeModel(new int[99][99]);
        int[][] raw=new int[99][99]; for(int r=0;r<99;r++) for(int c=0;c<99;c++) raw[r][c]=1;
        state.maze=new me.monstermazeai.maze.MazeModel(raw);
        state.activePadRow=55; state.activePadColumn=50;
        state.player.x=50.5; state.player.z=50.5; state.player.grounded=true;
        Action action=agent.decide(state,true);
        assertTrue(action.sprint());
    }
}
