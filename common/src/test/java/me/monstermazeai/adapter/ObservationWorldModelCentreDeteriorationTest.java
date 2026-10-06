package me.monstermazeai.adapter;

import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.Cell;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ObservationWorldModelCentreDeteriorationTest {
    private static int[][] maze() {
        int[][] raw = new int[99][99];
        raw[49][49] = 3;
        raw[49][50] = 4;
        raw[49][48] = 5;
        raw[50][49] = 6;
        raw[49][47] = 1;
        raw[49][51] = 1;
        return raw;
    }

    private static boolean[][] physical(boolean centreClayPresent) {
        boolean[][] floor = new boolean[99][99];
        int[][] raw = maze();
        for (int r = 0; r < 99; r++) {
            for (int c = 0; c < 99; c++) {
                floor[r][c] = raw[r][c] != 0;
            }
        }
        if (!centreClayPresent) {
            floor[49][49] = false;
            floor[49][50] = false;
        }
        return floor;
    }

    private static LegacyWorldObservation observation(boolean centreClayPresent) {
        int[][] raw = maze();
        return new LegacyWorldObservation(
                600,
                true,
                true,
                1,
                true,
                false,
                1,
                60,
                30,
                new LegacyWorldObservation.Player(
                        0.5, 64.0, 0.5,
                        0, 0, 0, 0, 0, true, 20, 20),
                Kit.JUMPER,
                1,
                0,
                new LegacyWorldObservation.BlockPoint(0, 64, 0),
                null,
                raw,
                physical(centreClayPresent),
                new ArrayList<LegacyWorldObservation.Monster>(),
                "Monster Maze",
                List.of("Safe Pad", "60 Seconds", "Stage", "1"));
    }

    @Test
    void centreClayIsPhysicalPlayerFloorBeforeDeterioration() {
        GameState state = ObservationWorldModel.from(observation(true));

        assertTrue(state.maze.isPhysicalFloor(49, 49));
        assertTrue(state.maze.isPhysicalFloor(49, 48));
        assertTrue(state.maze.isDisabled(49, 48),
                "Centre path cells remain disabled for monsters before deterioration.");
        assertTrue(new me.monstermazeai.maze.PlayerPathfinder()
                .shortestPath(state.maze, new Cell(49, 47), new Cell(49, 49)).size() > 0);
    }

    @Test
    void finalDeteriorationRemovesClayAndReenablesCentrePaths() {
        GameState state = ObservationWorldModel.from(observation(false));

        assertFalse(state.maze.isPhysicalFloor(49, 49));
        assertFalse(state.maze.isPhysicalFloor(49, 50));
        assertTrue(state.maze.isPhysicalFloor(49, 48));
        assertFalse(state.maze.isDisabled(49, 48),
                "Centre path cells become normal monster waypoints after final deterioration.");
        assertFalse(state.maze.isDisabled(50, 49));
    }
}
