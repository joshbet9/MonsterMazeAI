package me.monstermazeai.adapter;

import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
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
        assertEquals(3, state.mazePattern);
        assertTrue(state.inMonsterMaze);
        assertEquals(50.2, state.player.x, 1e-9);
        assertEquals(50.7, state.player.z, 1e-9);
        assertEquals(49.8, state.monsters.get(0).x, 1e-9);
        assertEquals(49.4, state.monsters.get(0).z, 1e-9);
        assertEquals(49, state.monsters.get(0).waypointRow);
        assertEquals(49, state.monsters.get(0).waypointColumn);
        assertEquals(3, state.ability.charges);
        assertEquals(50, state.activePadRow);
        assertEquals(50, state.activePadColumn);
        assertEquals(1, state.maze.raw(50, 50));
        assertTrue(state.maze.isDisabled(50, 50));
        assertTrue(state.maze.isDisabled(48, 48));
        assertFalse(state.maze.isDisabled(47, 47));
    }
    @Test
    void usesAuthoritativeSpeedModeFromLiveObservation() {
        int[][] maze = new int[99][99];
        maze[50][50] = 1;

        LegacyWorldObservation observation = new LegacyWorldObservation(
                1234, true, true, 3, "SPEED", true, false, 1, 60, 12,
                new LegacyWorldObservation.Player(
                        24.2, 14.0, 16.7, 0.1, -0.2, 0.3,
                        90f, -5f, true, 18, 20),
                Kit.MAVERICK, 0, 0,
                new LegacyWorldObservation.BlockPoint(23, 13, 15),
                new LegacyWorldObservation.Pad(50, 50, 4.0, false),
                maze, Collections.emptyList(),
                "Monster Maze", Arrays.asList("Mode", "Speed", "Safe Pad", "60 Seconds", "Stage", "1"));

        GameState state = ObservationWorldModel.from(observation);

        assertEquals(me.monstermazeai.game.Mode.SPEED, state.mode);
    }

    @Test
    void usesLiveJumperChargeCountAsCommonAbilityCharges() {
        int[][] maze = new int[99][99];
        maze[50][50] = 1;

        LegacyWorldObservation observation = new LegacyWorldObservation(
                55, true, true, 1, "SPEED", true, false, 1, 60, 0,
                new LegacyWorldObservation.Player(
                        0.5, 0.0, 0.5, 0.0, 0.0, 0.0,
                        0f, 0f, true, 20, 20),
                Kit.JUMPER, 2, 99,
                new LegacyWorldObservation.BlockPoint(0, 0, 0),
                new LegacyWorldObservation.Pad(50, 50, 0.0, false),
                maze, Collections.emptyList(), "Monster Maze",
                Arrays.asList("Mode", "Speed", "Safe Pad", "60 Seconds", "Stage", "1"));

        GameState state = ObservationWorldModel.from(observation);

        assertEquals(2, state.player.jumpCharges);
        assertEquals(2, state.ability.charges,
                "the live Jumper feather count must drive the same ability state used by the simulator");
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

    @Test
    void reconstructsRepulsorOrBodyRushLaunchStateFromLiveMobMotion() {
        int[][] maze = new int[99][99];
        maze[10][10] = 1;

        LegacyWorldObservation observation = new LegacyWorldObservation(
                500, true, true, 1, "SPEED", true, false, 4, 40, 8,
                new LegacyWorldObservation.Player(
                        10.5, 10.0, 10.5, 0.0, 0.0, 0.0,
                        0f, 0f, false, 20, 20),
                Kit.REPULSOR, 0, 1,
                new LegacyWorldObservation.BlockPoint(0, 0, 0),
                new LegacyWorldObservation.Pad(30, 30, 0.0, false),
                maze, Collections.emptyList(),
                "Monster Maze", Arrays.asList("Mode", "Speed", "Safe Pad", "40 Seconds", "Stage", "4"));

        LegacyWorldObservation.Monster launched = new LegacyWorldObservation.Monster(
                22, "monster_maze_monster", "snowman",
                10.9, 10.9, 10.5, 0.25, 1.0, 0.15, false);

        observation = new LegacyWorldObservation(
                500, true, true, 1, "SPEED", true, false, 4, 40, 8,
                observation.player, observation.kit, observation.jumpCharges,
                observation.abilityCharges, observation.center, observation.pad,
                observation.maze, observation.physicalFloor,
                Collections.singletonList(launched),
                observation.scoreboardTitle, observation.scoreboardLines);

        GameState state = ObservationWorldModel.from(observation);

        assertEquals(499L, state.monsters.get(0).launchedAtTick);
        assertEquals(529L, state.monsters.get(0).launchedUntilTick);
        assertTrue(state.monsters.get(0).launched(state.tick));
    }

}
