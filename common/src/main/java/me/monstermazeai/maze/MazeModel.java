package me.monstermazeai.maze;

import java.util.ArrayList;
import java.util.List;

public final class MazeModel {
    public static final int SIZE = 99;

    private final int[][] raw;
    private final boolean[][] disabled;

    public MazeModel(int[][] raw) {
        if (raw.length != SIZE) throw new IllegalArgumentException("Maze must be 99x99");
        this.raw = new int[SIZE][SIZE];
        this.disabled = new boolean[SIZE][SIZE];
        for (int r = 0; r < SIZE; r++) {
            if (raw[r].length != SIZE) throw new IllegalArgumentException("Maze must be 99x99");
            System.arraycopy(raw[r], 0, this.raw[r], 0, SIZE);
        }
    }

    public int raw(int row, int col) { return raw[row][col]; }
    public boolean isRawPath(int row, int col) {
        int v = raw[row][col];
        return v == 1 || v == 2 || v == 5 || v == 6;
    }
    public boolean isDisabled(int row, int col) { return disabled[row][col]; }
    public void setDisabled(int row, int col, boolean value) { disabled[row][col] = value; }
    public MazeModel copy() {
        MazeModel copy = new MazeModel(raw);
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                copy.disabled[r][c] = disabled[r][c];
            }
        }
        return copy;
    }

    public boolean isTraversable(int row, int col) {
        return row >= 0 && row < SIZE && col >= 0 && col < SIZE
                && isRawPath(row, col) && !disabled[row][col];
    }

    public List<Cell> cardinalNeighbours(Cell cell) {
        int r = cell.row(), c = cell.column();
        List<Cell> out = new ArrayList<>(4);
        if (isTraversable(r - 1, c)) out.add(new Cell(r - 1, c));
        if (isTraversable(r + 1, c)) out.add(new Cell(r + 1, c));
        if (isTraversable(r, c + 1)) out.add(new Cell(r, c + 1));
        if (isTraversable(r, c - 1)) out.add(new Cell(r, c - 1));
        return out;
    }
}