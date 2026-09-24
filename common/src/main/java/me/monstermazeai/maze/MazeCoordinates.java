package me.monstermazeai.maze;

import me.monstermazeai.adapter.LegacyWorldObservation;

/**
 * Converts between Monster Maze world coordinates and its 99x99 logical
 * maze coordinates using the source's centre-relative indexing.
 */
public final class MazeCoordinates {
    public static final int HALF_MAZE = (MazeModel.SIZE - 1) / 2;

    private final int centerX, centerY, centerZ;

    public MazeCoordinates(LegacyWorldObservation.BlockPoint center) {
        if (center == null) throw new IllegalArgumentException("center");
        centerX = center.x; centerY = center.y; centerZ = center.z;
    }

    public int centerX() { return centerX; }
    public int centerY() { return centerY; }
    public int centerZ() { return centerZ; }

    public int row(double worldX) {
        return (int) Math.floor(worldX - (centerX - HALF_MAZE));
    }

    public int column(double worldZ) {
        return (int) Math.floor(worldZ - (centerZ - HALF_MAZE));
    }

    public double worldX(int row) {
        requireCell(row);
        return centerX - HALF_MAZE + row + 0.5D;
    }

    public double worldZ(int column) {
        requireCell(column);
        return centerZ - HALF_MAZE + column + 0.5D;
    }

    public double[] worldCentre(Cell cell) {
        if (cell == null) throw new IllegalArgumentException("cell");
        return new double[]{worldX(cell.row()), worldZ(cell.column())};
    }

    public Cell containingCell(double worldX, double worldZ) {
        return new Cell(row(worldX), column(worldZ));
    }

    public boolean inBounds(Cell cell) {
        return cell != null
                && cell.row() >= 0 && cell.row() < MazeModel.SIZE
                && cell.column() >= 0 && cell.column() < MazeModel.SIZE;
    }

    /** Player/path Y is the detected centre Y; the maze surface is one block below. */
    public int pathY() { return centerY; }
    public int surfaceY() { return centerY - 1; }

    private static void requireCell(int coordinate) {
        if (coordinate < 0 || coordinate >= MazeModel.SIZE) {
            throw new IllegalArgumentException("Maze coordinate out of bounds: " + coordinate);
        }
    }
}