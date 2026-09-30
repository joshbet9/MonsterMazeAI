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

            /*
             * Source MonsterManager.move() teleports a snowman back to the
             * nearest path when its real entity has fallen below its current
             * waypoint Y. Snowmen are real 0.7-wide entities, so a diagonal
             * ControllerMove turn can briefly leave the one-block path and
             * enter the void. The old simulator kept normal mobs at y=0 forever,
             * which let them cut corners and remain able to bump the player from
             * positions the real entity could not occupy.
             */
            if (m.y < 0.0D) {
                Cell recovery = nearestPathCell(m.x, m.z);
                if (recovery != null) {
                    m.x = recovery.row() + CELL_CENTER_OFFSET;
                    m.z = recovery.column() + CELL_CENTER_OFFSET;
                    m.y = 0.0D;
                    m.vx = 0.0D;
                    m.vz = 0.0D;
                }
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
            // ControllerMove passes the command speed unchanged; it is
            // multiplied only by GenericAttributes.MOVEMENT_SPEED.
            double movementInput = speed * SNOWMAN_MOVEMENT_SPEED;
            float desiredYaw = (float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
            m.yaw = approachAngle(m.yaw, desiredYaw, 30.0F);

            // EntityLiving.g() receives the controller's forward movement input,
            // accelerates motX/motZ, moves the bounding box, then applies the
            // quartz path block's 0.6 slipperiness * 0.91 friction multiplier.
            double yaw = Math.toRadians(m.yaw);
            double forwardX = -Math.sin(yaw);
            double forwardZ = Math.cos(yaw);
            m.vx += forwardX * movementInput;
            m.vz += forwardZ * movementInput;
            double stepSq = m.vx * m.vx + m.vz * m.vz;
            double distance = Math.hypot(dx, dz);
            if (stepSq > 0.0D) {
                m.x += m.vx;
                m.z += m.vz;
            }

            /*
             * EntitySnowman is 0.7 blocks wide in 1.8.9. On the source maze's
             * one-block floating path, support exists while any part of that
             * horizontal AABB overlaps a physical path block. Lose support and
             * the entity falls; MonsterManager's next move tick then performs
             * the nearest-path teleport above.
             */
            if (!hasPhysicalSupport(m.x, m.z)) {
                m.y = -0.08D;
            } else {
                m.y = 0.0D;
            }

            m.vx *= GROUND_FRICTION;
            m.vz *= GROUND_FRICTION;
        }
    }

    private void chooseNextWaypoint(MonsterState m, Cell current) {
        // Exact MonsterManager selection:
        // 1) collect all cardinal waypoint blocks;
        // 2) when there is more than one choice, remove the immediate reverse;
        // 3) choose one with the manager RNG;
        // 4) walk that direction until getTarget() reaches a branch where more
        //    than one non-forward waypoint exists.
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

        Cell target = chosen;
        Cell cursor = current;
        while (true) {
            Cell next = new Cell(
                    cursor.row() + direction.dr,
                    cursor.column() + direction.dc);
            if (!maze.isTraversable(next.row(), next.column())) break;

            target = next;

            int alternatives = 0;
            for (Cell neighbour : maze.cardinalNeighbours(next)) {
                CardinalDirection candidateDirection = CardinalDirection.between(
                        neighbour.row() - next.row(), neighbour.column() - next.column());
                if (candidateDirection != direction) alternatives++;
            }
            if (alternatives > 1) break;

            cursor = next;
        }

        m.waypointRow = target.row();
        m.waypointColumn = target.column();
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

    private boolean hasPhysicalSupport(double x, double z) {
        final double halfWidth = 0.35D;
        double minX = x - halfWidth;
        double maxX = x + halfWidth;
        double minZ = z - halfWidth;
        double maxZ = z + halfWidth;
        int minRow = (int) Math.floor(minX);
        int maxRow = (int) Math.floor(Math.nextDown(maxX));
        int minColumn = (int) Math.floor(minZ);
        int maxColumn = (int) Math.floor(Math.nextDown(maxZ));

        for (int row = minRow; row <= maxRow; row++) {
            for (int column = minColumn; column <= maxColumn; column++) {
                if (maze.isTraversable(row, column)) return true;
            }
        }
        return false;
    }

    private Cell nearestPathCell(double x, double z) {
        Cell best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int row = 0; row < MazeModel.SIZE; row++) {
            for (int column = 0; column < MazeModel.SIZE; column++) {
                if (!maze.isTraversable(row, column)) continue;
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
