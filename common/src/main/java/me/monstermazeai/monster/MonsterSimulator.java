package me.monstermazeai.monster;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MazeModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Source-aligned MonsterManager movement simulator.
 *
 * The common simulator deliberately matches the standalone engine's current
 * realized controller model: one cardinal step per tick, 0.14 blocks/tick at
 * the 1.4 Monster Maze controller value, no artificial acceleration, and hard
 * exclusion of Safe Pad surfaces / non-physical floor.
 */
public final class MonsterSimulator {
    private static final double WAYPOINT_TOLERANCE = 0.4;
    private static final double CELL_CENTER_OFFSET = 0.5;
    private static final double GRAVITY = 0.08;
    private static final double AIR_DRAG = 0.9800000190734863D;

    private static final double REALIZED_MOVE_SCALE = 0.10D;
    private static final double MAX_REALIZED_MOVE_PER_TICK = 0.14D;

    private final MazeModel maze;
    private final Random random;
    private final double speed;
    private final long seed;

    public MonsterSimulator(MazeModel maze, Random random, double speed) {
        this.maze = maze;
        this.random = random;
        this.speed = speed;
        this.seed = random.nextLong();
    }

    /**
     * Live-game constructor that preserves the caller's Random stream.
     */
    public MonsterSimulator(MazeModel maze, Random random, double speed, long seed) {
        if (maze == null) throw new IllegalArgumentException("maze");
        if (random == null) throw new IllegalArgumentException("random");
        this.maze = maze;
        this.random = random;
        this.speed = speed;
        this.seed = seed;
    }

    private MonsterSimulator(MazeModel maze, long seed, double speed) {
        this.maze = maze;
        this.random = new Random(seed);
        this.speed = speed;
        this.seed = seed;
    }

    public void tick(GameState state) {
        for (MonsterState m : state.monsters) {
            m.lastDx = 0.0D;
            m.lastDy = 0.0D;
            m.lastDz = 0.0D;

            if (m.removed || m.frozen(state.tick)) continue;

            double startX = m.x;
            double startY = m.y;
            double startZ = m.z;

            if (m.launched(state.tick)) {
                tickLaunched(state, m);
                recordDelta(m, startX, startY, startZ);
                continue;
            }

            // Recover mobs that have lost their path support, matching the
            // standalone engine's nearest-live-path recovery behaviour.
            if (m.y < GameState.PATH_Y) {
                int row = nearestRow(m.x);
                int col = nearestColumn(m.z);
                if (validCell(row, col) && maze.isRawPath(row, col) && !maze.hasPadSurface(row, col)) {
                    m.x = row + CELL_CENTER_OFFSET;
                    m.z = col + CELL_CENTER_OFFSET;
                    m.y = GameState.PATH_Y;
                    m.vx = 0.0D;
                    m.vz = 0.0D;
                    m.waypointRow = row;
                    m.waypointColumn = col;
                }
            }

            // Safe Pad footprint is a hard exclusion for the mob body.
            if (overlapsPadSurface(m.x, m.z)) {
                int row = nearestRow(m.x);
                int col = nearestColumn(m.z);
                Cell exit = findPadExit(row, col);
                if (exit == null) {
                    m.removed = true;
                    continue;
                }
                m.waypointRow = exit.row();
                m.waypointColumn = exit.column();
                m.direction = CardinalDirection.between(
                        exit.row() - row, exit.column() - col);
            }

            int tr = m.waypointRow;
            int tc = m.waypointColumn;

            // Invalidate stale targets immediately when a Safe Pad / decay
            // change disables the old waypoint.
            if (tr >= 0 && tc >= 0 && !maze.isTraversable(tr, tc)) {
                m.waypointRow = -1;
                m.waypointColumn = -1;
                m.direction = CardinalDirection.NONE;
                tr = -1;
                tc = -1;
            }

            if (tr < 0 || tc < 0) {
                int row = nearestRow(m.x);
                int col = nearestColumn(m.z);
                if (!validCell(row, col) || !maze.isTraversable(row, col)) continue;
                Cell next = chooseNextWaypoint(m, row, col);
                if (next == null) continue;
                tr = next.row();
                tc = next.column();
            } else if (atWaypoint(m, tr + CELL_CENTER_OFFSET, tc + CELL_CENTER_OFFSET)) {
                m.x = tr + CELL_CENTER_OFFSET;
                m.z = tc + CELL_CENTER_OFFSET;
                Cell next = chooseNextWaypoint(m, tr, tc);
                if (next == null) continue;
                tr = next.row();
                tc = next.column();
            }

            CardinalDirection direction = m.direction;
            if (direction == CardinalDirection.NONE) {
                direction = CardinalDirection.between(
                        tr - nearestRow(m.x), tc - nearestColumn(m.z));
                m.direction = direction;
            }

            double movementInput = Math.min(
                    MAX_REALIZED_MOVE_PER_TICK, speed * REALIZED_MOVE_SCALE);

            double vx = 0.0D;
            double vz = 0.0D;
            switch (direction) {
                case NORTH -> vx = -movementInput;
                case SOUTH -> vx = movementInput;
                case EAST -> vz = movementInput;
                case WEST -> vz = -movementInput;
                default -> {
                    continue;
                }
            }

            double nx = m.x + vx;
            double nz = m.z + vz;

            int currentRow = nearestRow(m.x);
            int currentCol = nearestColumn(m.z);
            int nextRow = nearestRow(nx);
            int nextCol = nearestColumn(nz);

            if (!validCell(nextRow, nextCol)
                    || !maze.isTraversable(nextRow, nextCol)
                    || maze.hasPadSurface(nextRow, nextCol)
                    || !maze.isPhysicalFloor(nextRow, nextCol)) {
                m.vx = 0.0D;
                m.vz = 0.0D;
                if (nextRow != currentRow || nextCol != currentCol) {
                    m.waypointRow = -1;
                    m.waypointColumn = -1;
                    m.direction = CardinalDirection.NONE;
                }
                continue;
            }

            if (!hasPhysicalSupport(nx, nz)) {
                m.vx = 0.0D;
                m.vz = 0.0D;
                continue;
            }

            m.vx = vx;
            m.vz = vz;
            m.vy = 0.0D;
            m.x = nx;
            m.y = GameState.PATH_Y;
            m.z = nz;

            // Do not allow the full 0.7-wide mob body to occupy a Safe Pad.
            if (overlapsPadSurface(m.x, m.z)) {
                m.x -= vx;
                m.z -= vz;
                m.vx = 0.0D;
                m.vz = 0.0D;
                m.waypointRow = -1;
                m.waypointColumn = -1;
                m.direction = CardinalDirection.NONE;
            }

            recordDelta(m, startX, startY, startZ);
        }
    }

    private Cell chooseNextWaypoint(MonsterState m, int row, int col) {
        List<Cell> choices = new ArrayList<>(maze.cardinalNeighbours(new Cell(row, col)));
        CardinalDirection currentDirection = m.direction;
        if (currentDirection == CardinalDirection.NONE
                && m.waypointRow >= 0 && m.waypointColumn >= 0) {
            currentDirection = CardinalDirection.between(
                    m.waypointRow - row, m.waypointColumn - col);
        }

        if (choices.size() > 1 && currentDirection != CardinalDirection.NONE) {
            CardinalDirection reverse = currentDirection.opposite();
            choices.removeIf(c -> CardinalDirection.between(
                    c.row() - row, c.column() - col) == reverse);
        }

        if (choices.isEmpty()) {
            m.waypointRow = -1;
            m.waypointColumn = -1;
            m.direction = CardinalDirection.NONE;
            return null;
        }

        Cell chosen = choices.get(random.nextInt(choices.size()));
        CardinalDirection direction = CardinalDirection.between(
                chosen.row() - row, chosen.column() - col);

        int tr = chosen.row();
        int tc = chosen.column();
        int cr = row;
        int cc = col;

        while (true) {
            int nr = cr + direction.dr;
            int nc = cc + direction.dc;
            if (!maze.isTraversable(nr, nc)) break;

            tr = nr;
            tc = nc;

            int alternatives = 0;
            for (Cell n : maze.cardinalNeighbours(new Cell(nr, nc))) {
                CardinalDirection nd = CardinalDirection.between(
                        n.row() - nr, n.column() - nc);
                if (nd != direction) alternatives++;
            }
            if (alternatives > 1) break;

            cr = nr;
            cc = nc;
        }

        m.waypointRow = tr;
        m.waypointColumn = tc;
        m.direction = direction;
        return new Cell(tr, tc);
    }

    private Cell findPadExit(int row, int col) {
        if (!validCell(row, col)) return null;
        List<Cell> exits = maze.cardinalNeighbours(new Cell(row, col));
        if (exits.isEmpty()) return null;

        Cell best = exits.get(0);
        int centre = MazeModel.SIZE / 2;
        int bestDistance = Math.abs(best.row() - centre) + Math.abs(best.column() - centre);

        for (Cell candidate : exits) {
            int d = Math.abs(candidate.row() - centre) + Math.abs(candidate.column() - centre);
            if (d > bestDistance) {
                best = candidate;
                bestDistance = d;
            }
        }
        return best;
    }

    private boolean overlapsPadSurface(double x, double z) {
        final double halfWidth = 0.35D;
        int minRow = nearestRow(x - halfWidth);
        int maxRow = nearestRow(Math.nextDown(x + halfWidth));
        int minCol = nearestColumn(z - halfWidth);
        int maxCol = nearestColumn(Math.nextDown(z + halfWidth));

        for (int r = minRow; r <= maxRow; r++) {
            for (int c = minCol; c <= maxCol; c++) {
                if (validCell(r, c) && maze.hasPadSurface(r, c)) return true;
            }
        }
        return false;
    }

    private boolean hasPhysicalSupport(double x, double z) {
        final double halfWidth = 0.35D;
        int minRow = nearestRow(x - halfWidth);
        int maxRow = nearestRow(Math.nextDown(x + halfWidth));
        int minCol = nearestColumn(z - halfWidth);
        int maxCol = nearestColumn(Math.nextDown(z + halfWidth));

        for (int r = minRow; r <= maxRow; r++) {
            for (int c = minCol; c <= maxCol; c++) {
                if (maze.isTraversable(r, c)) return true;
            }
        }
        return false;
    }

    private Cell nearestPathCell(double x, double z) {
        Cell best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int row = 0; row < MazeModel.SIZE; row++) {
            for (int column = 0; column < MazeModel.SIZE; column++) {
                if (!maze.isRawPath(row, column) || maze.hasPadSurface(row, column)) continue;
                double dx = (row + CELL_CENTER_OFFSET) - x;
                double dz = (column + CELL_CENTER_OFFSET) - z;
                double distance = dx * dx + dz * dz;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new Cell(row, column);
                }
            }
        }
        return best;
    }

    private int nearestRow(double x) {
        return (int) Math.floor(x);
    }

    private int nearestColumn(double z) {
        return (int) Math.floor(z);
    }

    private boolean validCell(int row, int col) {
        return row >= 0 && row < MazeModel.SIZE && col >= 0 && col < MazeModel.SIZE;
    }

    private static void recordDelta(MonsterState m, double startX, double startY, double startZ) {
        m.lastDx = m.x - startX;
        m.lastDy = m.y - startY;
        m.lastDz = m.z - startZ;
    }

    private static void tickLaunched(GameState state, MonsterState m) {
        m.x += m.vx;
        m.y += m.vy;
        m.z += m.vz;
        m.vy -= GRAVITY;
        m.vy *= AIR_DRAG;
        m.vx *= AIR_DRAG;
        m.vz *= AIR_DRAG;

        if (m.y <= GameState.PATH_Y) {
            m.y = GameState.PATH_Y;
            m.vy = 0.0D;
            if (state.tick - m.launchedAtTick >= 10) {
                m.removed = true;
            } else {
                m.launchedUntilTick = 0L;
                m.launchedAtTick = 0L;
            }
        } else if (state.tick - m.launchedAtTick >= 30) {
            m.removed = true;
        }
    }

    private static boolean atWaypoint(MonsterState m, double x, double z) {
        return Math.hypot(m.x - x, m.z - z) < WAYPOINT_TOLERANCE;
    }

    public MonsterSimulator fork(long seed) {
        return new MonsterSimulator(maze, seed, speed);
    }

    public double speed() { return speed; }
    public long seed() { return seed; }
}
