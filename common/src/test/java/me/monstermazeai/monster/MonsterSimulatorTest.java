package me.monstermazeai.monster;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.MazeModel;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonsterSimulatorTest {
    private static MazeModel straightCorridor() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int column = 10; column <= 80; column++) raw[49][column] = 1;
        return new MazeModel(raw);
    }

    @Test
    void sourceGroundMovementUsesOnePointFourCommandOnce() {
        MazeModel maze = straightCorridor();
        GameState state = new GameState();
        state.maze = maze;

        MonsterState monster = new MonsterState(1, 49.5, GameState.PATH_Y, 20.5);
        state.monsters.add(monster);

        MonsterSimulator simulator = new MonsterSimulator(
                maze, new Random(1234L), 1.4, 1234L);

        for (int i = 0; i < 24; i++) simulator.tick(state);

        double realizedBlocksPerTick = Math.hypot(monster.lastDx, monster.lastDz);

        // 1.8 ground movement on a 0.6-slipperiness block with a 1.4
        // ControllerMove command converges to about 0.308 blocks/tick.
        assertEquals(0.30837, realizedBlocksPerTick, 0.004);
        assertTrue(realizedBlocksPerTick < 0.35,
                "mob movement must not use the 1.4 command twice");
    }
}
