package me.monstermazeai.monster;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MazeModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Source-derived MonsterManager movement simulator.
 *
 * MonsterManager chooses a cardinal neighbour, avoids immediately reversing
 * when alternatives exist, then compresses that direction into a waypoint and
 * uses ControllerMove/CreatureMoveFast toward it. The simulator deliberately
 * keeps a deterministic RNG per planning branch.
 */
public final class MonsterSimulator {
    private static final double WAYPOINT_TOLERANCE = 0.4;
    private static final double CELL_CENTER_OFFSET = 0.5;
    private static final double GRAVITY = 0.08;
    private static final double AIR_DRAG = 0.98;
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

    private MonsterSimulator(MazeModel maze, long seed, double speed) {
        this.maze = maze;
        this.random = new Random(seed);
        this.speed = speed;
        this.seed = seed;
    }

    public void tick(GameState state) {
        for (MonsterState m : state.monsters) {
            if (m.removed || m.frozen(state.tick)) continue;
            if (m.launched(state.tick)) {
                tickLaunched(state, m);
                continue;
            }

            if (m.waypointRow < 0 || atWaypoint(m, m.waypointRow + 0.5, m.waypointColumn + 0.5)) {
                Cell current = nearestCell(m.x, m.z);
                if (current != null) chooseNextWaypoint(m, current);
            }

            if (m.waypointRow < 0) continue;
            double tx = m.waypointRow + 0.5;
            double tz = m.waypointColumn + 0.5;
            double dx = tx - m.x, dz = tz - m.z;
            double distance = Math.hypot(dx, dz);
            if (distance < 1.0E-9) continue;

            double step = Math.min(speed, distance);
            m.vx = dx / distance * step;
            m.vz = dz / distance * step;
            m.x += m.vx;
            m.z += m.vz;
        }
    }

    private void chooseNextWaypoint(MonsterState m, Cell current) {
        List<Cell> choices = new ArrayList<>(maze.cardinalNeighbours(current));
        if (choices.size() > 1 && m.direction != CardinalDirection.NONE) {
            choices.removeIf(c -> CardinalDirection.between(
                    c.row() - current.row(), c.column() - current.column())
                    == m.direction.opposite());
        }
        if (choices.isEmpty()) {
            m.waypointRow = -1;
            m.waypointColumn = -1;
            m.direction = CardinalDirection.NONE;
            return;
        }

        Cell chosen = choices.get(random.nextInt(choices.size()));
        CardinalDirection direction = CardinalDirection.between(
                chosen.row() - current.row(), chosen.column() - current.column());

        Cell terminal = chosen;
        Cell cursor = chosen;
        while (true) {
            Cell cursorCell = cursor;
            List<Cell> forward = new ArrayList<>(maze.cardinalNeighbours(cursorCell));
            forward.removeIf(c -> CardinalDirection.between(
                    c.row() - cursorCell.row(), c.column() - cursorCell.column()) != direction);
            if (forward.isEmpty()) break;

            Cell next = forward.get(0);
            List<Cell> atNext = maze.cardinalNeighbours(next);
            int alternatives = 0;
            for (Cell n : atNext) {
                CardinalDirection d = CardinalDirection.between(
                        n.row() - next.row(), n.column() - next.column());
                if (d != direction) alternatives++;
            }
            if (alternatives > 1) {
                terminal = next;
                break;
            }
            terminal = next;
            cursor = next;
        }

        m.waypointRow = terminal.row();
        m.waypointColumn = terminal.column();
        m.direction = direction;
    }

    private void tickLaunched(GameState state, MonsterState m) {
        m.x += m.vx;
        m.y += m.vy;
        m.z += m.vz;
        m.vy -= GRAVITY;
        m.vy *= AIR_DRAG;
        m.vx *= AIR_DRAG;
        m.vz *= AIR_DRAG;
        if (m.y <= 0.0) {
            m.y = 0.0;
            m.vy = 0.0;
            if (state.tick - m.launchedAtTick >= 10) m.removed = true;
        } else if (state.tick - m.launchedAtTick >= 30) {
            m.removed = true;
        }
    }

    private Cell nearestCell(double x, double z) {
        int row = (int)Math.floor(x);
        int col = (int)Math.floor(z);
        if (row < 0 || col < 0 || row >= MazeModel.SIZE || col >= MazeModel.SIZE) return null;
        return maze.isTraversable(row, col) ? new Cell(row, col) : null;
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
