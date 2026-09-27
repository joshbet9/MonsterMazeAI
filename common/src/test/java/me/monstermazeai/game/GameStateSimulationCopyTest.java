package me.monstermazeai.game;

import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.monster.MonsterState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GameStateSimulationCopyTest {
    @Test
    void simulationCopySharesMazeButDeepCopiesMutableSimulationState() {
        GameState source = new GameState();
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        raw[10][10] = 1;
        source.maze = new MazeModel(raw);
        source.player.x = 10.5;
        source.monsters.add(new MonsterState(1, 11.5, 0.0, 10.5));

        GameState copy = source.copyForSimulation();

        assertSame(source.maze, copy.maze);
        assertNotSame(source.player, copy.player);
        assertNotSame(source.monsters, copy.monsters);
        assertNotSame(source.monsters.get(0), copy.monsters.get(0));

        copy.player.x = 99.0;
        copy.monsters.get(0).x = 77.0;

        assertEquals(10.5, source.player.x);
        assertEquals(11.5, source.monsters.get(0).x);
    }
}
