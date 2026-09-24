package me.monstermazeai.adapter;

import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class ObservationWorldModelTest {
    @Test
    void mapsLiveWorldIntoLogicalMazeCoordinates() {
        int[][] maze = new int[99][99];
        maze[50][50] = 1;

        LegacyWorldObservation observation = new LegacyWorldObservation(
                1234, true, true, 3, true, false, 1, 60, 12,
                new LegacyWorldObservation.Player(
                        24.2, 14.0, 16.7, 0.1, -0.2, 0.3,
                        90f, -5f, true, 18, 20),
                Kit.REPULSOR, 2, 3,
                new LegacyWorldObservation.BlockPoint(23, 13, 15),
                new LegacyWorldObservation.Pad(50, 50, 4.0, false),
                maze, Collections.emptyList(), "Monster Maze",
                Collections.singletonList("1"));

        GameState state = ObservationWorldModel.from(observation);

        assertEquals(1234, state.tick);
        assertTrue(state.inMonsterMaze);
        assertEquals(50.0, state.player.x);
        assertEquals(50.0, state.player.z);
        assertEquals(50, state.activePadRow);
        assertEquals(50, state.activePadColumn);
        assertEquals(1, state.maze.raw(50, 50));
    }
}
