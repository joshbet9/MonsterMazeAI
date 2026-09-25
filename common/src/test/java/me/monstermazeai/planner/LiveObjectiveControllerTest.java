package me.monstermazeai.planner;

import me.monstermazeai.collision.CollisionModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.physics.LegacyMazePhysics;
import me.monstermazeai.player.Action;
import me.monstermazeai.sim.Simulator;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class LiveObjectiveControllerTest {
    private static MazeModel openMaze() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int row = 0; row < MazeModel.SIZE; row++) {
            for (int column = 0; column < MazeModel.SIZE; column++) {
                raw[row][column] = 1;
            }
        }
        return new MazeModel(raw);
    }

    private static LiveObjectiveController controller() {
        MazeModel maze = openMaze();
        Simulator simulator = new Simulator(
                new LegacyMazePhysics(),
                new MonsterSimulator(maze, new Random(7), 0.0),
                new CollisionModel());
        return new LiveObjectiveController(
                new MazeAwareRecedingHorizonController(
                        new BeamSearchPlanner(simulator, new Heuristic(), 8, 4), 1));
    }

    private static GameState activeState(int padRow, int padColumn) {
        GameState state = new GameState();
        state.inMonsterMaze = true;
        state.maze = openMaze();
        state.activePadRow = padRow;
        state.activePadColumn = padColumn;
        state.phaseTicksRemaining = 700;
        state.player.x = 0.5;
        state.player.z = 1.5;
        state.player.grounded = true;
        return state;
    }

    @Test
    void pursuesTheAuthoritativeActivePad() {
        Action action = controller().nextAction(activeState(4, 1), false);
        assertNotEquals(Action.IDLE, action);
    }

    @Test
    void arrivingInsidePadGeometryCompletesObjectiveEvenIfAdapterFlagIsStale() {
        GameState state = activeState(4, 1);
        state.player.x = 4.5;
        state.player.z = 1.5;

        assertEquals(Action.IDLE, controller().nextAction(state, false));
    }

    @Test
    void expiredObjectiveFailsClosed() {
        GameState state = activeState(4, 1);
        state.phaseTicksRemaining = 0;

        assertEquals(Action.IDLE, controller().nextAction(state, false));
    }

    @Test
    void blockedTargetFailsClosed() {
        GameState state = activeState(4, 1);
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        raw[0][1] = 1;
        state.maze = new MazeModel(raw);

        assertEquals(Action.IDLE, controller().nextAction(state, false));
    }

    @Test
    void newlyObservedPadBecomesTheNextObjective() {
        LiveObjectiveController controller = controller();
        GameState state = activeState(4, 1);

        Action first = controller.nextAction(state, false);
        assertNotEquals(Action.IDLE, first);

        state.activePadRow = 8;
        state.activePadColumn = 8;
        state.player.x = 2.5;
        state.player.z = 2.5;

        Action second = controller.nextAction(state, false);
        assertNotEquals(Action.IDLE, second);
        assertNotEquals(first, second,
                "A promoted pad must be selected from the newest observation.");
    }
}
