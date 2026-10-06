package me.monstermazeai.minecraft.v18;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MazeLayoutsTest {
    @Test
    public void containsAllAuthoritativeLayoutsAtExpectedSize() {
        assertEquals(3, MazeLayouts.ALL_MAZES.length);

        for (int[][] maze : MazeLayouts.ALL_MAZES) {
            assertEquals(99, maze.length);

            int nonEmpty = 0;
            for (int[] row : maze) {
                assertEquals(99, row.length);
                for (int value : row) {
                    assertTrue("layout value must be in the source range 0..6",
                            value >= 0 && value <= 6);
                    if (value != 0) {
                        nonEmpty++;
                    }
                }
            }

            assertTrue("maze must contain a substantial playable area", nonEmpty >= 100);
        }
    }

    @Test
    public void layoutValuesPreserveLogicalCellTypes() {
        for (int[][] maze : MazeLayouts.ALL_MAZES) {
            int specialCells = 0;
            int centerCells = 0;

            for (int[] row : maze) {
                for (int value : row) {
                    if (value == 2) {
                        specialCells++;
                    }
                    if (value >= 3) {
                        centerCells++;
                    }
                }
            }

            assertTrue("layout must contain monster-spawn/special cells", specialCells > 0);
            assertTrue("layout must contain center-safe-zone cells", centerCells > 0);
        }
    }
}
