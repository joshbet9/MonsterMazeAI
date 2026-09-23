package me.monstermazeai.sim;

import me.monstermazeai.collision.CollisionModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.game.Mode;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.physics.LegacyMazePhysics;
import me.monstermazeai.player.Action;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MonsterTrajectoryPredictorTest {
    private static MazeModel openMaze() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int r = 0; r < MazeModel.SIZE; r++)
            for (int c = 0; c < MazeModel.SIZE; c++)
                raw[r][c] = 1;
        return new MazeModel(raw);
    }

    @Test
    void predictorUsesSimulatedMonsterCollisionRisk() {
        MazeModel maze = openMaze();
        GameState state = new GameState();
        state.mode = Mode.MODERN;
        state.maze = maze;
        state.player.x = 10.5;
        state.player.z = 10.5;
        state.player.grounded = true;
        state.monsters.add(new MonsterState(1, 11.0, 0.0, 10.5));

        Simulator simulator = new Simulator(
                new LegacyMazePhysics(),
                new MonsterSimulator(maze, new Random(7), 0.0),
                new CollisionModel());

        MonsterTrajectoryPredictor.Prediction prediction =
                new MonsterTrajectoryPredictor(simulator)
                        .predict(state, Action.IDLE, 1, 1234L);

        assertTrue(prediction.collisionRisk());
        assertTrue(prediction.damageTaken() >= 4.0);
        assertEquals(1, prediction.firstDangerTick());
    }
}
