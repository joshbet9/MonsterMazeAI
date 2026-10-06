package me.monstermazeai.planner;

import me.monstermazeai.collision.CollisionModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.game.Mode;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.physics.LegacyMazePhysics;
import me.monstermazeai.player.Action;
import me.monstermazeai.sim.Simulator;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RecedingHorizonControllerTest {
    private static MazeModel openMaze() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int r = 0; r < MazeModel.SIZE; r++)
            for (int c = 0; c < MazeModel.SIZE; c++)
                raw[r][c] = 1;
        return new MazeModel(raw);
    }

    @Test
    void executionWindowIsBoundedAndReplansFromObservedState() {
        GameState state = new GameState();
        state.mode = Mode.MODERN;
        state.maze = openMaze();
        state.phaseTicksRemaining = 700;
        state.activePadRow = 20;
        state.activePadColumn = 20;
        state.player.x = 12.5;
        state.player.z = 20.5;
        state.player.grounded = true;

        Simulator simulator = new Simulator(
                new LegacyMazePhysics(),
                new MonsterSimulator(state.maze, new Random(1234), 0.07),
                new CollisionModel());
        RecedingHorizonController controller = new RecedingHorizonController(
                new BeamSearchPlanner(simulator, new Heuristic(), 12, 6));

        Action[] first = controller.nextActions(
                state, state.targetPadX(), state.targetPadZ(), false, 3);

        assertTrue(first.length <= 3);
        assertTrue(first.length >= 1);

        for (Action action : first) simulator.tick(state, action);

        Action[] second = controller.nextActions(
                state, state.targetPadX(), state.targetPadZ(), false, 3);

        assertTrue(second.length <= 3);
        assertTrue(second.length >= 1);
    }
}
