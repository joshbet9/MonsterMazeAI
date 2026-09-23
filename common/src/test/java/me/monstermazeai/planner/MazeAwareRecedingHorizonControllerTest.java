package me.monstermazeai.planner;

import me.monstermazeai.collision.CollisionModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.game.Mode;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.physics.LegacyMazePhysics;
import me.monstermazeai.player.Action;
import me.monstermazeai.sim.Simulator;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MazeAwareRecedingHorizonControllerTest {
    private static MazeModel maze() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int r = 0; r < MazeModel.SIZE; r++)
            for (int c = 0; c < MazeModel.SIZE; c++)
                raw[r][c] = 1;
        return new MazeModel(raw);
    }

    @Test
    void plannerReceivesTheNextPhysicalMazeWaypoint() {
        MazeModel maze = maze();
        maze.setDisabled(1, 1, true);

        GameState state = new GameState();
        state.mode = Mode.MODERN;
        state.maze = maze;
        state.phaseTicksRemaining = 700;
        state.player.x = 0.5;
        state.player.z = 1.5;
        state.player.grounded = true;

        Simulator simulator = new Simulator(
                new LegacyMazePhysics(),
                new MonsterSimulator(maze, new Random(5), 0.0),
                new CollisionModel());

        MazeAwareRecedingHorizonController controller =
                new MazeAwareRecedingHorizonController(
                        new BeamSearchPlanner(simulator, new Heuristic(), 8, 4), 3);

        Action[] actions = controller.nextActions(
                state, new Cell(2, 1), false);

        assertTrue(actions.length >= 1);
        assertTrue(actions.length <= 3);
        assertNotEquals(Action.IDLE, actions[0],
                "The controller should drive toward the next route waypoint.");
    }
}
