package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.game.PadModel;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.maze.PlayerPathfinder;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.player.Action;
import me.monstermazeai.player.AiProfile;

import java.util.*;

/**
 * Deterministic Monster Maze controller.
 *
 * The game state already contains everything needed to solve the maze:
 * the exact active Safe Pad, exact physical floor, and current monster positions.
 * There is therefore no reason to use ML or global probabilistic route scoring
 * in the live loop.
 *
 * The policy is intentionally small:
 *   1. Compute the shortest physical route to the active Safe Pad.
 *   2. Follow that route with continuous forward steering instead of
 *      stop-turn-go waypoint control.
 *   3. Start a corner turn slightly before the corner so momentum is preserved.
 *   4. When a monster actually blocks the next few cells, temporarily route
 *      around that local obstacle and rejoin the committed route.
 *   5. Use a gap jump only when the chosen physical route genuinely requires it.
 *
 * The route is otherwise sticky. Monster motion does not cause the whole maze
 * to be reconsidered, which prevents oscillation and preserves human-like speed.
 */
public final class DeterministicMazeController {
    private static final float MAX_TURN_PER_TICK = 30.0F;
    private static final double CORNER_LOOKAHEAD = 0.80D;
    private static final double WAYPOINT_ADVANCE = 0.58D;
    private static final double ROUTE_DEVIATION = 0.95D;
    private static final double IMMEDIATE_MONSTER_RADIUS = 2.75D;
    private static final double BLOCKING_MONSTER_RADIUS = 1.05D;
    private static final int THREAT_LOOKAHEAD_CELLS = 4;
    private static final int REJOIN_LOOKAHEAD_CELLS = 8;
    private static final int REJOIN_SEARCH_RADIUS = 2;
    private static final double GAP_TRIGGER_DISTANCE = 0.95D;
    private static final double GAP_LATERAL_LIMIT = 0.55D;
    private static final double MOB_RECOVERY_HEALTH_EPS = 0.25D;

    private final AiProfile profile;
    private final PlayerPathfinder pathfinder = new PlayerPathfinder();

    private PlayerRoute route;
    private int waypointIndex;
    private int goalRow = -1;
    private int goalColumn = -1;
    private int goalRadius;
    private long routePlanCount;
    private long lastRouteTick = Long.MIN_VALUE;
    private long lastThreatRerouteTick = Long.MIN_VALUE;
    private long lastSpeedJumpTick = Long.MIN_VALUE;
    private double previousHealth = Double.NaN;
    private String lastDecisionDetail = "RESET";

    public DeterministicMazeController(AiProfile profile) {
        if (profile == null) throw new IllegalArgumentException("profile");
        this.profile = profile;
    }

    public AiProfile profile() {
        return profile;
    }

    public long routePlanCount() {
        return routePlanCount;
    }

    public long lastRouteTick() {
        return lastRouteTick;
    }

    public String lastDecisionDetail() {
        return lastDecisionDetail;
    }

    public Action nextAction(GameState state, Cell goal, boolean allowJump, int regionRadius) {
        if (state == null || state.maze == null || goal == null || regionRadius < 0) {
            reset();
            lastDecisionDetail = "INVALID_INPUT";
            return Action.IDLE;
        }

        boolean objectiveChanged = goal.row() != goalRow
                || goal.column() != goalColumn
                || regionRadius != goalRadius;

        if (objectiveChanged) {
            route = null;
            waypointIndex = 0;
            goalRow = goal.row();
            goalColumn = goal.column();
            goalRadius = regionRadius;
            lastThreatRerouteTick = Long.MIN_VALUE;
        }

        boolean damaged = detectDamage(state);
        if (damaged) {
            route = null;
            waypointIndex = 0;
            if (!state.player.grounded) {
                lastDecisionDetail = "MOB_RECOVERY airborne";
                return Action.IDLE;
            }
        }

        if (regionRadius > 0 && PadModel.isOn(
                state.player,
                goal.row() + 0.5,
                GameState.PAD_SURFACE_Y,
                goal.column() + 0.5)) {
            route = null;
            lastDecisionDetail = "REACHED_SAFE_PAD";
            return Action.IDLE;
        }

        Cell start = supportedCell(state);
        if (start == null) {
            route = null;
            lastDecisionDetail = "NO_SUPPORTED_CELL";
            return Action.IDLE;
        }

        if (route == null
                || route.size() < 2
                || !route.cells().contains(start)
                || routeOutsidePlayerCorridor(state, route) > ROUTE_DEVIATION) {
            route = buildShortestRoute(state, start, goal, regionRadius);
            waypointIndex = chooseStartingWaypoint(state, route);
            routePlanCount++;
            lastRouteTick = state.tick;
            lastThreatRerouteTick = Long.MIN_VALUE;
            lastDecisionDetail = "NEW_SHORTEST_ROUTE size=" + route.size();
        } else {
            advanceWaypoint(state);

            if (shouldRerouteAroundImmediateMonster(state)
                    && state.tick - lastThreatRerouteTick >= 3L) {
                PlayerRoute local = buildLocalDetour(state, start, goal, regionRadius);
                if (local != null
                        && local.size() >= 2
                        && !sameRoute(local, route)) {
                    route = local;
                    waypointIndex = chooseStartingWaypoint(state, route);
                    routePlanCount++;
                    lastRouteTick = state.tick;
                    lastThreatRerouteTick = state.tick;
                    lastDecisionDetail = "LOCAL_MONSTER_DETOUR size=" + route.size();
                } else {
                    lastThreatRerouteTick = state.tick;
                }
            }
        }

        advanceWaypoint(state);

        if (route == null || route.size() <= 1 || waypointIndex >= route.size()) {
            lastDecisionDetail = "ROUTE_COMPLETE";
            return Action.IDLE;
        }

        Cell from = route.cells().get(Math.max(0, waypointIndex - 1));
        Cell to = route.cells().get(waypointIndex);

        if (isGapEdge(state.maze, from, to)) {
            Action gap = gapAction(state, from, to, allowJump);
            if (gap != null) return gap;
        }

        Target target = steeringTarget(state, from, to);
        float desiredYaw = yawTo(state.player.x, state.player.z, target.x, target.z);
        float yawError = normalise(desiredYaw - state.player.yaw);
        float yawDelta = clamp(yawError, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);

        /*
         * Use both WASD axes to carry the player through a corner. Minecraft's
         * movement normalises the input vector, so sin/cos(error) gives a
         * full-strength directional command rather than slowing the player to
         * turn the camera first. The look-ahead target is on the outgoing
         * physical corridor, so the vector naturally cuts the corner while
         * yaw converges toward the new cardinal heading.
         */
        double forward;
        double strafe;
        boolean sprint;

        if (Math.abs(yawError) > 115.0F) {
            forward = 0.0D;
            strafe = 0.0D;
            sprint = false;
        } else if (Math.abs(yawError) > 8.0F) {
            double errorRad = Math.toRadians(yawError);
            forward = Math.cos(errorRad);
            strafe = -Math.sin(errorRad);
            sprint = true;
        } else {
            forward = 1.0D;
            strafe = 0.0D;
            sprint = true;
        }

        boolean jump = shouldSpeedJump(state, allowJump);

        lastDecisionDetail =
                "DRIVE route=" + route.size()
                        + " waypoint=" + waypointIndex + "/" + (route.size() - 1)
                        + " target=" + format(target.x) + "," + format(target.z)
                        + " yawError=" + format(yawError)
                        + " forward=" + format(forward)
                        + " strafe=" + format(strafe)
                        + " jump=" + jump;

        return new Action(forward, strafe, jump, sprint, yawDelta, false);
    }

    private PlayerRoute buildShortestRoute(
            GameState state, Cell start, Cell goal, int radius) {
        List<Cell> noGap = pathfinder.shortestPathToRegionWithoutGaps(
                state.maze, start, goal, radius);
        if (!noGap.isEmpty()) return new PlayerRoute(noGap);

        List<Cell> withGap = pathfinder.shortestPathToRegion(
                state.maze, start, goal, radius);
        if (withGap.isEmpty()) {
            throw new IllegalArgumentException("No physical route to Safe Pad");
        }
        return new PlayerRoute(withGap);
    }

    /**
     * Replan only the local portion of the currently committed route.
     * The target is one of the next few cells on that route, so a monster can
     * move the player around an obstacle without changing the whole strategy.
     */
    private PlayerRoute buildLocalDetour(
            GameState state, Cell start, Cell goal, int radius) {
        if (route == null || route.size() < 2) return null;

        Set<Cell> blocked = new HashSet<>();
        for (MonsterState monster : state.monsters) {
            if (monster == null || monster.removed
                    || monster.launched(state.tick)
                    || monster.frozen(state.tick)) {
                continue;
            }

            double distance = Math.hypot(
                    monster.x - state.player.x,
                    monster.z - state.player.z);
            if (distance > IMMEDIATE_MONSTER_RADIUS) continue;

            int row = (int) Math.floor(monster.x);
            int col = (int) Math.floor(monster.z);
            for (int dr = -1; dr <= 1; dr++) {
                for (int dc = -1; dc <= 1; dc++) {
                    Cell candidate = new Cell(row + dr, col + dc);
                    if (candidate.row() < 0 || candidate.row() >= MazeModel.SIZE
                            || candidate.column() < 0 || candidate.column() >= MazeModel.SIZE) {
                        continue;
                    }
                    if (candidate.equals(start)) continue;
                    if (state.maze.isPhysicalFloor(candidate.row(), candidate.column())) {
                        blocked.add(candidate);
                    }
                }
            }
        }

        if (blocked.isEmpty()) return null;

        List<Cell> current = route.cells();
        int begin = Math.max(waypointIndex, 1);
        int end = Math.min(current.size() - 1, begin + REJOIN_LOOKAHEAD_CELLS);

        PlayerRoute best = null;
        double bestCost = Double.POSITIVE_INFINITY;

        for (int i = begin; i <= end; i++) {
            Cell target = current.get(i);
            if (blocked.contains(target)) continue;

            for (boolean allowGaps : new boolean[]{false, true}) {
                List<Cell> candidate = bfsAvoiding(
                        state.maze, start, target, blocked, allowGaps);
                if (candidate.isEmpty()) continue;

                double cost = candidate.size()
                        + (allowGaps ? 1.5D * countGaps(candidate) : 0.0D)
                        + 0.10D * i;
                if (cost < bestCost) {
                    bestCost = cost;
                    best = new PlayerRoute(candidate);
                }
            }
        }

        return best;
    }

    private List<Cell> bfsAvoiding(
            MazeModel maze, Cell start, Cell goal, Set<Cell> blocked, boolean allowGaps) {
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        Map<Cell, Cell> previous = new HashMap<>();
        queue.add(start);
        previous.put(start, null);

        while (!queue.isEmpty()) {
            Cell current = queue.removeFirst();
            if (current.equals(goal)) return reconstruct(previous, goal);

            int r = current.row();
            int c = current.column();

            List<Cell> neighbours = new ArrayList<>(4);
            neighbours.add(new Cell(r - 1, c));
            neighbours.add(new Cell(r + 1, c));
            neighbours.add(new Cell(r, c - 1));
            neighbours.add(new Cell(r, c + 1));

            if (allowGaps) {
                neighbours.add(new Cell(r - 2, c));
                neighbours.add(new Cell(r + 2, c));
                neighbours.add(new Cell(r, c - 2));
                neighbours.add(new Cell(r, c + 2));
            }

            for (Cell next : neighbours) {
                if (!validTransition(maze, current, next, allowGaps)) continue;
                if (blocked.contains(next) && !next.equals(goal)) continue;
                if (previous.containsKey(next)) continue;
                previous.put(next, current);
                queue.addLast(next);
            }
        }

        return List.of();
    }

    private boolean validTransition(MazeModel maze, Cell from, Cell to, boolean allowGaps) {
        if (!maze.isPhysicalFloor(to.row(), to.column())) return false;
        int dr = to.row() - from.row();
        int dc = to.column() - from.column();
        if (Math.abs(dr) + Math.abs(dc) == 1) return true;
        if (!allowGaps) return false;
        if (!((Math.abs(dr) == 2 && dc == 0)
                || (Math.abs(dc) == 2 && dr == 0))) return false;

        int middleRow = from.row() + Integer.signum(dr);
        int middleColumn = from.column() + Integer.signum(dc);
        return !maze.isPhysicalFloor(middleRow, middleColumn);
    }

    private static List<Cell> reconstruct(Map<Cell, Cell> previous, Cell goal) {
        ArrayList<Cell> path = new ArrayList<>();
        for (Cell at = goal; at != null; at = previous.get(at)) path.add(at);
        Collections.reverse(path);
        return path;
    }

    private Target steeringTarget(GameState state, Cell from, Cell to) {
        double fromX = from.row() + 0.5D;
        double fromZ = from.column() + 0.5D;
        double toX = to.row() + 0.5D;
        double toZ = to.column() + 0.5D;

        int dirRow = Integer.signum(to.row() - from.row());
        int dirColumn = Integer.signum(to.column() - from.column());

        if (isGapEdge(state.maze, from, to)) {
            return new Target(toX, toZ);
        }

        double progress;
        if (dirRow != 0) {
            progress = (state.player.x - fromX) * dirRow;
        } else {
            progress = (state.player.z - fromZ) * dirColumn;
        }

        /*
         * Start turning before the corner. The target is a point just beyond
         * the corner on the outgoing corridor, which produces a smooth
         * continuous arc rather than a stop at the corner.
         */
        if (progress >= 1.0D - CORNER_LOOKAHEAD
                && waypointIndex + 1 < route.size()) {
            Cell next = route.cells().get(waypointIndex + 1);
            if (!isGapEdge(state.maze, to, next)) {
                int nextRowDir = Integer.signum(next.row() - to.row());
                int nextColumnDir = Integer.signum(next.column() - to.column());
                return new Target(
                        toX + nextRowDir * CORNER_LOOKAHEAD,
                        toZ + nextColumnDir * CORNER_LOOKAHEAD);
            }
        }

        return new Target(toX, toZ);
    }

    private Action gapAction(GameState state, Cell from, Cell to, boolean allowJump) {
        int dr = Integer.signum(to.row() - from.row());
        int dc = Integer.signum(to.column() - from.column());

        double startX = from.row() + 0.5D;
        double startZ = from.column() + 0.5D;
        double progress = dr != 0
                ? (state.player.x - startX) * dr
                : (state.player.z - startZ) * dc;
        double lateral = dr != 0
                ? state.player.z - startZ
                : state.player.x - startX;

        float desiredYaw = dr > 0 ? -90.0F : dr < 0 ? 90.0F : dc > 0 ? 0.0F : 180.0F;
        float yawError = normalise(desiredYaw - state.player.yaw);

        if (Math.abs(lateral) > GAP_LATERAL_LIMIT) {
            return new Action(
                    0.0D, 0.0D, false, false,
                    clamp(yawError, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK),
                    false);
        }

        if (progress >= 1.0D - GAP_TRIGGER_DISTANCE && allowJump) {
            return new Action(
                    1.0D, 0.0D, true, true,
                    clamp(yawError, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK),
                    false);
        }

        return new Action(
                1.0D, 0.0D, shouldSpeedJump(state, allowJump),
                true,
                clamp(yawError, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK),
                false);
    }

    private boolean shouldSpeedJump(GameState state, boolean allowJump) {
        if (!allowJump || !state.player.grounded || state.kit == Kit.JUMPER) return false;

        long cadence = profile.attributes.nonJumperJumpCadenceTicks();
        if (state.tick - lastSpeedJumpTick < cadence) return false;
        lastSpeedJumpTick = state.tick;
        return true;
    }

    private boolean shouldRerouteAroundImmediateMonster(GameState state) {
        if (route == null || waypointIndex <= 0 || waypointIndex >= route.size()) return false;

        Cell from = route.cells().get(waypointIndex - 1);
        Cell to = route.cells().get(waypointIndex);

        double dx = to.row() - from.row();
        double dz = to.column() - from.column();
        double length = Math.max(1.0D, Math.hypot(dx, dz));
        dx /= length;
        dz /= length;

        for (MonsterState monster : state.monsters) {
            if (monster == null || monster.removed
                    || monster.launched(state.tick)
                    || monster.frozen(state.tick)) continue;

            double mx = monster.x - state.player.x;
            double mz = monster.z - state.player.z;
            double distance = Math.hypot(mx, mz);
            if (distance > IMMEDIATE_MONSTER_RADIUS) continue;

            double forwardDistance = mx * dx + mz * dz;
            if (forwardDistance < -0.35D
                    || forwardDistance > THREAT_LOOKAHEAD_CELLS) continue;

            double lateral = Math.abs(mx * dz - mz * dx);
            if (lateral <= BLOCKING_MONSTER_RADIUS) return true;
        }

        return false;
    }

    private void advanceWaypoint(GameState state) {
        while (route != null && waypointIndex < route.size() - 1) {
            Cell from = route.cells().get(waypointIndex - 1);
            Cell to = route.cells().get(waypointIndex);

            if (isGapEdge(state.maze, from, to)) {
                break;
            }

            double distance = Math.hypot(
                    state.player.x - (to.row() + 0.5D),
                    state.player.z - (to.column() + 0.5D));
            if (distance > WAYPOINT_ADVANCE) break;

            waypointIndex++;
        }
    }

    private int chooseStartingWaypoint(GameState state, PlayerRoute candidate) {
        if (candidate == null || candidate.size() < 2) return candidate == null ? 0 : candidate.size();

        int best = Math.min(1, candidate.size() - 1);
        double bestDistance = Double.POSITIVE_INFINITY;

        for (int i = 0; i < candidate.size() - 1; i++) {
            Cell a = candidate.cells().get(i);
            Cell b = candidate.cells().get(i + 1);

            double ax = a.row() + 0.5D;
            double az = a.column() + 0.5D;
            double bx = b.row() + 0.5D;
            double bz = b.column() + 0.5D;

            double dx = bx - ax;
            double dz = bz - az;
            double lenSq = dx * dx + dz * dz;
            if (lenSq < 1.0E-9D) continue;

            double px = state.player.x - ax;
            double pz = state.player.z - az;
            double t = Math.max(0.0D, Math.min(1.0D, (px * dx + pz * dz) / lenSq));
            double nx = ax + t * dx;
            double nz = az + t * dz;
            double distance = Math.hypot(state.player.x - nx, state.player.z - nz);

            if (distance < bestDistance) {
                bestDistance = distance;
                best = i + 1;
            }
        }

        return Math.max(1, best);
    }

    private double routeOutsidePlayerCorridor(GameState state, PlayerRoute candidate) {
        double best = Double.POSITIVE_INFINITY;
        List<Cell> cells = candidate.cells();

        for (int i = 0; i + 1 < cells.size(); i++) {
            Cell a = cells.get(i);
            Cell b = cells.get(i + 1);
            double ax = a.row() + 0.5D;
            double az = a.column() + 0.5D;
            double bx = b.row() + 0.5D;
            double bz = b.column() + 0.5D;

            double dx = bx - ax;
            double dz = bz - az;
            double lenSq = dx * dx + dz * dz;
            if (lenSq < 1.0E-9D) continue;

            double px = state.player.x - ax;
            double pz = state.player.z - az;
            double t = Math.max(0.0D, Math.min(1.0D, (px * dx + pz * dz) / lenSq));
            double nx = ax + t * dx;
            double nz = az + t * dz;
            best = Math.min(best, Math.hypot(state.player.x - nx, state.player.z - nz));
        }

        return best == Double.POSITIVE_INFINITY ? 0.0D : best;
    }

    private Cell supportedCell(GameState state) {
        int row = (int) Math.floor(state.player.x);
        int column = (int) Math.floor(state.player.z);
        if (state.maze.isPhysicalFloor(row, column)) {
            return new Cell(row, column);
        }

        Cell best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                int r = row + dr;
                int c = column + dc;
                if (!state.maze.isPhysicalFloor(r, c)) continue;
                double distance = Math.hypot(
                        state.player.x - (r + 0.5D),
                        state.player.z - (c + 0.5D));
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new Cell(r, c);
                }
            }
        }
        return best;
    }

    private static int countGaps(List<Cell> cells) {
        int count = 0;
        for (int i = 0; i + 1 < cells.size(); i++) {
            int dr = Math.abs(cells.get(i + 1).row() - cells.get(i).row());
            int dc = Math.abs(cells.get(i + 1).column() - cells.get(i).column());
            if ((dr == 2 && dc == 0) || (dc == 2 && dr == 0)) count++;
        }
        return count;
    }

    private static boolean isGapEdge(MazeModel maze, Cell from, Cell to) {
        int dr = to.row() - from.row();
        int dc = to.column() - from.column();
        if (!((Math.abs(dr) == 2 && dc == 0)
                || (Math.abs(dc) == 2 && dr == 0))) return false;

        int middleRow = from.row() + Integer.signum(dr);
        int middleColumn = from.column() + Integer.signum(dc);
        return maze.isPhysicalFloor(from.row(), from.column())
                && !maze.isPhysicalFloor(middleRow, middleColumn)
                && maze.isPhysicalFloor(to.row(), to.column());
    }

    private static float yawTo(double x, double z, double targetX, double targetZ) {
        return (float) Math.toDegrees(
                Math.atan2(-(targetX - x), targetZ - z));
    }

    private static float normalise(float angle) {
        while (angle >= 180.0F) angle -= 360.0F;
        while (angle < -180.0F) angle += 360.0F;
        return angle;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static boolean sameRoute(PlayerRoute a, PlayerRoute b) {
        return a != null && b != null && a.cells().equals(b.cells());
    }

    private boolean detectDamage(GameState state) {
        if (!Double.isFinite(previousHealth)) {
            previousHealth = state.player.health;
            return false;
        }
        boolean damaged = previousHealth - state.player.health >= MOB_RECOVERY_HEALTH_EPS;
        previousHealth = state.player.health;
        return damaged;
    }

    public void reset() {
        route = null;
        waypointIndex = 0;
        goalRow = -1;
        goalColumn = -1;
        goalRadius = 0;
        routePlanCount = 0;
        lastRouteTick = Long.MIN_VALUE;
        lastThreatRerouteTick = Long.MIN_VALUE;
        lastSpeedJumpTick = Long.MIN_VALUE;
        previousHealth = Double.NaN;
        lastDecisionDetail = "RESET";
    }

    private record Target(double x, double z) {}
}
