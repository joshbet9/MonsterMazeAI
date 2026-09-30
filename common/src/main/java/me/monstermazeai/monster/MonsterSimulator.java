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
    private static final double AIR_DRAG = 0.9800000190734863D;
    private static final double SNOWMAN_MOVEMENT_SPEED = 0.20000000298023224D;
    private static final double GROUND_SLIPPERINESS = 0.6D;
    private static final double GROUND_FRICTION = GROUND_SLIPPERINESS * 0.91D;
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
     * Live-game constructor that preserves the caller's Random stream. The
     * source MonsterManager shares one RNG across spawning and movement, so
     * authentic end-to-end simulation must not consume an extra value here.
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
            double horizontalSq = dx * dx + dz * dz;
            if (horizontalSq < 2.500000277905201E-7D) continue;

            // Source UtilEnt.CreatureMoveFast -> ControllerMove.c(): command
            // speed is multiplied by the Snowman's 0.2 movement attribute,
            // and the entity turns toward the waypoint by at most 30 degrees.
            double command = speed;
            if (horizontalSq < 4.0D) command = Math.min(command, 1.0D);
            double movementInput = command * SNOWMAN_MOVEMENT_SPEED;
            float desiredYaw = (float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
            m.yaw = approachAngle(m.yaw, desiredYaw, 30.0F);

            // EntityLiving.g() receives the controller's forward movement input,
            // accelerates motX/motZ, moves the bounding box, then applies the
            // quartz path block's 0.6 slipperiness * 0.91 friction multiplier.
            double yaw = Math.toRadians(m.yaw);
            double forwardX = -Math.sin(yaw);
            double forwardZ = Math.cos(yaw);
            m.vx += forwardX * movementInput * 0.98D;
            m.vz += forwardZ * movementInput * 0.98D;
            double stepSq = m.vx * m.vx + m.vz * m.vz;
            double distance = Math.hypot(dx, dz);
            if (stepSq > 0.0D) {
                // Entity.move() can stop a mob at an edge; the tactical simulator
                // does not own the block AABB, so retain the velocity and leave
                // floor/edge death to the caller's physical model.
                m.x += m.vx;
                m.z += m.vz;
            }
            m.vx *= GROUND_FRICTION;
            m.vz *= GROUND_FRICTION;
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

    private static float approachAngle(float current, float target, float maximumDelta) {
        float delta = normaliseDegrees(target - current);
        if (delta > maximumDelta) delta = maximumDelta;
        if (delta < -maximumDelta) delta = -maximumDelta;
        float result = current + delta;
        while (result < -180.0F) result += 360.0F;
        while (result >= 180.0F) result -= 360.0F;
        return result;
    }

    private static float normaliseDegrees(float angle) {
        while (angle <= -180.0F) angle += 360.0F;
        while (angle > 180.0F) angle -= 360.0F;
        return angle;
    }

    public MonsterSimulator fork(long seed) {
        return new MonsterSimulator(maze, seed, speed);
    }

    public double speed() { return speed; }
    public long seed() { return seed; }
}
