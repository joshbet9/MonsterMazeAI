package me.monstermazeai.monster;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.MazeModel;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression tests for source MonsterManager / CreatureMoveFast semantics. */
class MonsterSimulatorTest {
    @Test
    void capsCreatureMoveFastCommandSpeedWithinTwoBlocksOfWaypoint() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int r = 0; r < MazeModel.SIZE; r++) {
            for (int c = 0; c < MazeModel.SIZE; c++) raw[r][c] = 1;
        }
        MazeModel maze = new MazeModel(raw);
        MonsterSimulator simulator = new MonsterSimulator(maze, new Random(1234L), 1.4D, 1234L);

        GameState state = new GameState();
        MonsterState monster = new MonsterState(1, 10.5, 0.0, 10.5);
        monster.waypointRow = 10;
        monster.waypointColumn = 11;
        monster.yaw = 0.0F;
        state.monsters.add(monster);

        simulator.tick(state);

        // Source UtilEnt.CreatureMoveFast changes 1.4 -> 1.0 for distance < 2,
        // then ControllerMove applies the snowman's 0.2 movement attribute.
        assertEquals(10.70D, monster.z, 1.0e-7);
        assertEquals(0.1092D, monster.vz, 1.0e-7);
    }
}
