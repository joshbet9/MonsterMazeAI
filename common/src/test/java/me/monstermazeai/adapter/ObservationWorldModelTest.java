package me.monstermazeai.adapter;

import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

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
                maze, List.of(new LegacyWorldObservation.Monster(
                        77, "monster_maze_monster", "villager",
                        23.8, 14.0, 15.4, 0.4, 0.0, -0.2, false)),
                "Monster Maze",
                Collections.singletonList("1"));

        GameState state = ObservationWorldModel.from(observation);

        assertEquals(1234, state.tick);
        assertTrue(state.inMonsterMaze);
        assertEquals(50.2, state.player.x, 1e-9);
        assertEquals(50.7, state.player.z, 1e-9);
        assertEquals(49.8, state.monsters.get(0).x, 1e-9);
        assertEquals(49.4, state.monsters.get(0).z, 1e-9);
        assertEquals(50, state.monsters.get(0).waypointRow);
        assertEquals(50, state.monsters.get(0).waypointColumn);
        assertEquals(3, state.ability.charges);
        assertEquals(50, state.activePadRow);
        assertEquals(50, state.activePadColumn);
        assertEquals(1, state.maze.raw(50, 50));
    }
    @Test
    void lobbyObservationDoesNotRequireMazeCenter() {
        int[][] maze = new int[99][99];
        LegacyWorldObservation observation = new LegacyWorldObservation(
                55, false, false, -1, true, false, 1, 0, 0,
                new LegacyWorldObservation.Player(
                        100.0, 70.0, -40.0, 0.0, 0.0, 0.0,
                        0f, 0f, true, 20, 20),
                Kit.JUMPER, 1, 0, null, null, maze,
                Collections.emptyList(), "Lobby", Collections.emptyList());

        GameState state = ObservationWorldModel.from(observation);

        assertFalse(state.inMonsterMaze);
        assertNull(state.maze);
        assertEquals(55, state.tick);
    }

}
