package me.monstermazeai.maze;

import me.monstermazeai.adapter.LegacyWorldObservation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MazeCoordinatesTest {
    @Test
    void convertsPatternOneCentre() {
        MazeCoordinates c = new MazeCoordinates(
                new LegacyWorldObservation.BlockPoint(0, 64, 0));
        assertEquals(49, c.row(0.5));
        assertEquals(49, c.column(0.5));
        assertEquals(0.5, c.worldX(49));
        assertEquals(0.5, c.worldZ(49));
        assertEquals(64, c.pathY());
        assertEquals(63, c.surfaceY());
    }

    @Test
    void convertsPatternTwoCentre() {
        MazeCoordinates c = new MazeCoordinates(
                new LegacyWorldObservation.BlockPoint(-3, 114, 3));
        assertEquals(49, c.row(-2.5));
        assertEquals(49, c.column(3.5));
        assertEquals(-2.5, c.worldX(49));
        assertEquals(3.5, c.worldZ(49));
    }

    @Test
    void convertsPatternThreeCentre() {
        MazeCoordinates c = new MazeCoordinates(
                new LegacyWorldObservation.BlockPoint(23, 13, 15));
        assertEquals(49, c.row(23.5));
        assertEquals(49, c.column(15.5));
        assertEquals(23.5, c.worldX(49));
        assertEquals(15.5, c.worldZ(49));
    }

    @Test
    void containingCellHandlesSpawnOffset() {
        MazeCoordinates c = new MazeCoordinates(
                new LegacyWorldObservation.BlockPoint(23, 13, 15));
        assertEquals(new Cell(50, 51), c.containingCell(24.0, 16.0));
        assertTrue(c.inBounds(c.containingCell(24.0, 16.0)));
    }

    @Test
    void rejectsInvalidLogicalCells() {
        MazeCoordinates c = new MazeCoordinates(
                new LegacyWorldObservation.BlockPoint(0, 64, 0));
        assertThrows(IllegalArgumentException.class, () -> c.worldX(99));
        assertThrows(IllegalArgumentException.class, () -> c.worldZ(-1));
    }
}