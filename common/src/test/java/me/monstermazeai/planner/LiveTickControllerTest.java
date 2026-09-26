package me.monstermazeai.planner;

import me.monstermazeai.collision.CollisionModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.physics.LegacyMazePhysics;
import me.monstermazeai.player.Action;
import me.monstermazeai.sim.Simulator;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class LiveTickControllerTest {
    private static MazeModel openMaze() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int row = 0; row < MazeModel.SIZE; row++) {
            for (int column = 0; column < MazeModel.SIZE; column++) {
                raw[row][column] = 1;
            }
        }
        return new MazeModel(raw);
    }

    private static LiveTickController controller() {
        MazeModel maze = openMaze();
        Simulator simulator = new Simulator(
                new LegacyMazePhysics(),
                new MonsterSimulator(maze, new Random(7), 0.0),
                new CollisionModel());

        return new LiveTickController(
                new MazeAwareRecedingHorizonController(
                        new BeamSearchPlanner(simulator, new Heuristic(), 8, 4), 1));
    }

    @Test
    void lobbyFailsClosedWithoutTryingToPlan() {
        GameState state = new GameState();
        state.inMonsterMaze = false;

        assertEquals(Action.IDLE, controller().nextAction(state, true));
    }

    @Test
    void missingPadFailsClosed() {
        GameState state = new GameState();
        state.inMonsterMaze = true;
        state.maze = openMaze();

        assertEquals(Action.IDLE, controller().nextAction(state, true));
    }

    @Test
    void reachedPadFailsClosed() {
        GameState state = new GameState();
        state.inMonsterMaze = true;
        state.maze = openMaze();
        state.activePadRow = 5;
        state.activePadColumn = 5;
        state.padReached = true;

        assertEquals(Action.IDLE, controller().nextAction(state, true));
    }

    @Test
    void activePadProducesExactlyOneLiveControl() {
        GameState state = new GameState();
        state.inMonsterMaze = true;
        state.maze = openMaze();
        state.activePadRow = 2;
        state.activePadColumn = 1;
        state.player.x = 0.5;
        state.player.z = 1.5;
        state.player.grounded = true;
        state.phaseTicksRemaining = 700;

        Action action = controller().nextAction(state, false);

        assertNotNull(action);
        assertNotEquals(Action.IDLE, action);
    }

    @Test
    void theDecisionIsRecomputedFromTheCurrentObservation() {
        GameState state = new GameState();
        state.inMonsterMaze = true;
        state.maze = openMaze();
        state.activePadRow = 3;
        state.activePadColumn = 1;
        state.player.x = 0.5;
        state.player.z = 1.5;
        state.player.grounded = true;
        state.phaseTicksRemaining = 700;

        LiveTickController live = controller();
        Action first = live.nextAction(state, false);

        state.player.x = 2.5;
        state.player.z = 3.5;
        state.player.yaw = -180.0F;
        Action second = live.nextAction(state, false);

        assertNotEquals(first, second,
                "A live controller must replan from the latest observed position rather than replaying a cached action.");
    }
}
