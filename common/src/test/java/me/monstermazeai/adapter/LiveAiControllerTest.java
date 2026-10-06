package me.monstermazeai.adapter;

import me.monstermazeai.collision.CollisionModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.physics.LegacyMazePhysics;
import me.monstermazeai.planner.BeamSearchPlanner;
import me.monstermazeai.planner.Heuristic;
import me.monstermazeai.planner.MazeAwareRecedingHorizonController;
import me.monstermazeai.player.Action;
import me.monstermazeai.sim.Simulator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class LiveAiControllerTest {
    @Test
    void observesExecutesAndReobserves() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int r = 0; r < MazeModel.SIZE; r++)
            for (int c = 0; c < MazeModel.SIZE; c++) raw[r][c] = 1;
        MazeModel maze = new MazeModel(raw);

        GameState state = new GameState();
        state.maze = maze;
        state.activePadRow = 4;
        state.activePadColumn = 4;
        state.player.x = 0.5;
        state.player.z = 0.5;
        state.player.grounded = true;
        state.phaseTicksRemaining = 700;

        Simulator simulator = new Simulator(
                new LegacyMazePhysics(),
                new MonsterSimulator(maze, new Random(1), 0.0),
                new CollisionModel());

        class FakeAdapter implements WorldAdapter {
            int observations;
            final List<Action> executed = new ArrayList<>();

            public WorldObservation observe() {
                observations++;
                return new WorldObservation(state);
            }

            public void execute(Action action) {
                executed.add(action);
                simulator.tick(state, action);
            }
        }

        FakeAdapter adapter = new FakeAdapter();
        LiveAiController ai = new LiveAiController(
                adapter,
                new MazeAwareRecedingHorizonController(
                        new BeamSearchPlanner(simulator, new Heuristic(), 8, 4), 2),
                2);

        ai.tick();
        ai.tick();

        assertEquals(2, adapter.observations);
        assertFalse(adapter.executed.isEmpty());
        assertNotNull(adapter.executed.get(0));
    }
}
