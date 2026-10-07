package me.monstermazeai.physics;

import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.player.PlayerState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MazeCollisionTest {
    private static MazeModel mazeWithBarrier() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        raw[49][48] = 1;
        raw[49][49] = 6;
        return new MazeModel(raw);
    }

    @Test
    void glassBarrierStopsHorizontalPlayerMovementBeforeTheCell() {
        MazeModel maze = mazeWithBarrier();
        PlayerState p = new PlayerState();
        p.x = 49.5;
        p.y = 0.0;
        p.z = 48.5;
        p.grounded = true;

        new MazeCollision(maze).move(p, 0.0, 0.0, 1.0);

        assertEquals(48.7, p.z, 1.0e-9,
                "the 0.6-wide player AABB must stop at the source glass barrier");
        assertFalse(maze.isPhysicalFloor(49, 49));
        assertTrue(maze.isPhysicalBarrier(49, 49));
    }

    @Test
    void centerDeteriorationRemovesBarrierAndMakesRawSixFloor() {
        MazeModel maze = mazeWithBarrier();
        maze.setCenterDeteriorated(true);

        PlayerState p = new PlayerState();
        p.x = 49.5;
        p.y = 0.0;
        p.z = 48.5;
        p.grounded = true;

        new MazeCollision(maze).move(p, 0.0, 0.0, 1.0);

        assertTrue(p.z > 49.0,
                "after deterioration raw 6 must behave as normal physical floor");
        assertTrue(maze.isPhysicalFloor(49, 49));
        assertFalse(maze.isPhysicalBarrier(49, 49));
    }

    @Test
    void safePadSurfaceMasksGlassBarrierForPlayerCollision() {
        MazeModel maze = mazeWithBarrier();
        maze.setPadSurface(49, 49, true);

        PlayerState p = new PlayerState();
        p.x = 49.5;
        p.y = 0.0;
        p.z = 48.5;
        p.grounded = true;

        new MazeCollision(maze).move(p, 0.0, 0.0, 1.0);

        assertTrue(p.z > 49.0,
                "an active SafePad replaces the barrier cell with a walkable surface");
        assertFalse(maze.isPhysicalBarrier(49, 49));
    }
}
