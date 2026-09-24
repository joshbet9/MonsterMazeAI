package me.monstermazeai.runtime;

import me.monstermazeai.adapter.ObservationWorldModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.physics.LegacyMazePhysics;
import me.monstermazeai.collision.CollisionModel;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.player.Action;
import me.monstermazeai.planner.BeamSearchPlanner;
import me.monstermazeai.planner.Heuristic;
import me.monstermazeai.planner.LiveObjectiveController;
import me.monstermazeai.planner.MazeAwareRecedingHorizonController;
import me.monstermazeai.planner.RobustLiveController;
import me.monstermazeai.sim.Simulator;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class AutonomousMonsterMazeAgentTest {
    @Test
    void autonomousLoopProducesActionsAndFollowsChangedObjective() {
        GameState state = liveState(50, 50, 55, 50);
        AutonomousMonsterMazeAgent agent = new AutonomousMonsterMazeAgent(controller());

        Action first = agent.decide(state, true);
        assertNotEquals(Action.IDLE, first);

        state.tick++;
        state.player.x += 0.2;
        state.activePadColumn = 55;
        Action second = agent.decide(state, true);
        assertNotEquals(Action.IDLE, second);
    }

    @Test
    void lobbyDeathAndCompletionResetToIdle() {
        AutonomousMonsterMazeAgent agent = new AutonomousMonsterMazeAgent(controller());
        GameState state = liveState(50, 50, 55, 50);

        assertNotEquals(Action.IDLE, agent.decide(state, true));

        state.tick++;
        state.inMonsterMaze = false;
        assertEquals(Action.IDLE, agent.decide(state, true));

        state.tick++;
        state.inMonsterMaze = true;
        state.alive = false;
        assertEquals(Action.IDLE, agent.decide(state, true));

        state.tick++;
        state.alive = true;
        state.completed = true;
        assertEquals(Action.IDLE, agent.decide(state, true));
    }

    @Test
    void staleObservationFailsClosed() {
        AutonomousMonsterMazeAgent agent = new AutonomousMonsterMazeAgent(controller());
        GameState state = liveState(50, 50, 55, 50);

        assertNotEquals(Action.IDLE, agent.decide(state, true));
        assertEquals(Action.IDLE, agent.decide(state, true));
    }

    @Test
    void abilityThreatIsPartOfAutonomousDecision() {
        GameState state = liveState(50, 50, 55, 50);
        state.kit = Kit.REPULSOR;
        state.ability.charges = 1;
        state.monsters.add(new MonsterState(1, 51.0, 0.0, 50.0));

        Action action = new AutonomousMonsterMazeAgent(controller()).decide(state, true);
        assertTrue(action.useAbility());
    }

    private static RobustLiveController controller() {
        MazeModel maze = openMaze();
        Simulator simulator = new Simulator(
                new LegacyMazePhysics(),
                new MonsterSimulator(maze, new Random(7L), 0.0),
                new CollisionModel());
        MazeAwareRecedingHorizonController movement =
                new MazeAwareRecedingHorizonController(
                        new BeamSearchPlanner(simulator, new Heuristic(), 8, 4), 1);
        return new RobustLiveController(new LiveObjectiveController(movement));
    }

    private static GameState liveState(int x, int z, int padRow, int padColumn) {
        GameState state = new GameState();
        state.inMonsterMaze = true;
        state.alive = true;
        state.phaseTicksRemaining = 600;
        state.mazePattern = 1;
        state.maze = openMaze();
        state.player.x = x + 0.5;
        state.player.z = z + 0.5;
        state.player.grounded = true;
        state.activePadRow = padRow;
        state.activePadColumn = padColumn;
        state.kit = Kit.JUMPER;
        return state;
    }

    private static MazeModel openMaze() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int r = 0; r < MazeModel.SIZE; r++) {
            java.util.Arrays.fill(raw[r], 1);
        }
        return new MazeModel(raw);
    }
}
