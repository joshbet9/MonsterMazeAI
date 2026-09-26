package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MonsterAwareRoutePlanner;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.player.Action;
import me.monstermazeai.sim.Simulator;

/**
 * Continuous movement layer for the perfect-AI baseline.
 *
 * Route generation decides which floor cells to traverse. This class decides
 * how to traverse that route at Minecraft-physics speed: it cuts corners when
 * the swept path is safe, anticipates braking, aligns the movement vector with
 * the route, and hands control to knockback recovery when a mob hit makes the
 * current trajectory unsafe.
 */
public final class OptimalMovementController {
    private static final double EDGE_MARGIN = 0.20;
    private static final double LOOKAHEAD = 0.70;
    private static final double BRAKE_DISTANCE = 1.10;
    private static final double SAMPLE_STEP = 0.06;
    private static final float MAX_YAW_DELTA = 18.0F;

    private final Simulator simulator;
    private final MonsterAwareRoutePlanner routePlanner = new MonsterAwareRoutePlanner();
    private final KnockbackRecoveryController recovery;
    private final double tolerance;
    private String detail = "UNSET";

    public OptimalMovementController(Simulator simulator, double tolerance) {
        if (simulator == null || tolerance <= 0.0) throw new IllegalArgumentException();
        this.simulator = simulator;
        this.recovery = new KnockbackRecoveryController(simulator);
        this.tolerance = tolerance;
    }

    public Action nextAction(GameState state, Cell goal, boolean allowJump) {
        if (state == null || state.maze == null || goal == null) {
            detail = "INVALID_INPUT";
            return Action.IDLE;
        }

        Action recoveryAction = recovery.nextAction(state, allowJump);
        if (recoveryAction != Action.IDLE) {
            detail = "KNOCKBACK_RECOVERY hitUntil=" + state.player.recentMobHitUntilTick
                    + " action=" + describe(recoveryAction);
            return recoveryAction;
        }

        int row = (int) Math.floor(state.player.x);
        int col = (int) Math.floor(state.player.z);
        if (!state.maze.isPhysicalFloor(row, col)
                || !state.maze.isPhysicalFloor(goal.row(), goal.column())) {
            detail = "UNSAFE_START_OR_GOAL";
            return Action.IDLE;
        }

        PlayerRoute route = routePlanner.route(state, new Cell(row, col), goal);
        if (route.reached(state.player.x, state.player.z, tolerance)) {
            detail = "ROUTE_REACHED";
            return Action.IDLE;
        }

        int waypoint = route.nextWaypoint(state.player.x, state.player.z, 0, tolerance);
        double[] target = continuousTarget(state, route, waypoint);
        Action action = choosePhysicsAwareAction(state, route, target[0], target[1], allowJump);

        detail = "ROUTE size=" + route.size()
                + " waypoint=" + waypoint + "/" + (route.size() - 1)
                + " target=" + target[0] + "," + target[1]
                + " speed=" + Math.hypot(state.player.vx, state.player.vz)
                + " action=" + describe(action);
        return action;
    }

    public String lastDecisionDetail() {
        return detail;
    }

    private double[] continuousTarget(GameState state, PlayerRoute route, int waypoint) {
        int anchor = Math.min(waypoint, route.size() - 1);
        double tx = route.targetX(anchor);
        double tz = route.targetZ(anchor);

        // Project ahead along the next route segment. This avoids the old
        // "aim at the centre of the next block" oscillation at corners.
        if (anchor < route.size() - 1) {
            double nx = route.targetX(anchor + 1);
            double nz = route.targetZ(anchor + 1);
            double len = Math.hypot(nx - tx, nz - tz);
            if (len > 1.0E-9) {
                double look = Math.min(LOOKAHEAD, len);
                double px = tx + (nx - tx) * look / len;
                double pz = tz + (nz - tz) * look / len;
                if (safeSegment(state, state.player.x, state.player.z, px, pz)) {
                    tx = px;
                    tz = pz;
                }
            }
        }

        // At a corner, look through the corner if the diagonal sweep is safe.
        for (int i = anchor + 1; i < route.size(); i++) {
            double nx = route.targetX(i), nz = route.targetZ(i);
            if (!safeSegment(state, state.player.x, state.player.z, nx, nz)) break;
            tx = nx;
            tz = nz;
            if (Math.hypot(tx - state.player.x, tz - state.player.z) > 2.5) break;
        }
        return new double[]{tx, tz};
    }

    private Action choosePhysicsAwareAction(GameState state, PlayerRoute route,
                                             double tx, double tz, boolean allowJump) {
        double dx = tx - state.player.x;
        double dz = tz - state.player.z;
        double distance = Math.hypot(dx, dz);
        if (distance < 1.0E-6) return Action.IDLE;

        // Convert the desired world-space direction into local Minecraft
        // forward/strafe input. This lets the AI keep moving toward the route
        // even while its camera is still rotating toward the ideal heading.
        double desiredYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double yawError = wrap(desiredYaw - state.player.yaw);

        double currentSpeed = Math.hypot(state.player.vx, state.player.vz);
        double stoppingDistance = currentSpeed * currentSpeed / 0.028;
        boolean braking = stoppingDistance > distance + 0.25
                || (distance < BRAKE_DISTANCE && Math.abs(yawError) > 70.0);

        double localAngle = Math.toRadians(wrap(desiredYaw - state.player.yaw));
        double forward = Math.cos(localAngle);
        double strafe = Math.sin(localAngle);
        if (braking) {
            forward = -0.35;
            strafe = 0.0;
        }

        int f = signInput(forward);
        int s = signInput(strafe);
        if (f == 0 && s == 0) f = 1;

        float yawDelta = clamp((float) yawError, -MAX_YAW_DELTA, MAX_YAW_DELTA);
        boolean jump = allowJump && jumpOpportunity(state, route, tx, tz);

        Action[] candidates = {
                new Action(f, s, jump, true, yawDelta, false),
                new Action(f, 0, jump, true, yawDelta, false),
                new Action(0, s, jump, true, yawDelta, false),
                new Action(0, 0, jump, true, yawDelta, false)
        };

        Action best = Action.IDLE;
        double bestScore = Double.POSITIVE_INFINITY;
        for (Action candidate : candidates) {
            GameState next = simulator.forecast(
                    state, candidate, 2, simulator.monsterSeed() ^ state.tick ^ 0x5DEECE66DL);
            if (!state.alive || !safePredictedTrajectory(state, next)) continue;

            double targetDistance = Math.hypot(next.player.x - tx, next.player.z - tz);
            double routeDistance = distanceToRoute(next.player.x, next.player.z, route);
            double edgePenalty = edgePenalty(next);
            double reversePenalty = braking ? Math.max(0.0, next.player.vx * forward
                    + next.player.vz * strafe) : 0.0;
            double score = targetDistance + 0.35 * routeDistance
                    + 3.0 * edgePenalty + 2.0 * reversePenalty;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best != Action.IDLE) return best;

        // Never turn a route-planning disagreement into a frozen player. The
        // simulator includes conservative edge margins that are useful for
        // scoring, but live 1.8 geometry can legitimately permit a trajectory
        // that the simplified common model rejects. If every strict candidate
        // was rejected, take the safest route-directed action whose projected
        // path still remains on physical floor.
        Action relaxed = relaxedRouteFallback(state, tx, tz, yawDelta);
        if (relaxed != Action.IDLE) {
            detail += " RELAXED_FALLBACK";
            return relaxed;
        }
        return new Action(0, 0, false, false, yawDelta, false);
    }

    private Action relaxedRouteFallback(GameState state, double tx, double tz, float yawDelta) {
        double dx = tx - state.player.x;
        double dz = tz - state.player.z;
        double distance = Math.hypot(dx, dz);
        if (distance < 1.0E-6) return Action.IDLE;

        double desiredYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double localAngle = Math.toRadians(wrap(desiredYaw - state.player.yaw));
        int f = signInput(Math.cos(localAngle));
        int s = signInput(Math.sin(localAngle));
        if (f == 0 && s == 0) f = 1;

        Action[] candidates = {
                new Action(f, s, false, true, yawDelta, false),
                new Action(f, 0, false, true, yawDelta, false),
                new Action(0, s, false, true, yawDelta, false)
        };
        for (Action candidate : candidates) {
            GameState next = simulator.forecast(
                    state, candidate, 2, simulator.monsterSeed() ^ state.tick ^ 0x2F6E2B1L);
            if (next.alive && floorOnlyTrajectory(state, next)) return candidate;
        }
        return Action.IDLE;
    }

    private boolean floorOnlyTrajectory(GameState source, GameState next) {
        if (!next.alive) return false;
        if (!isPhysicalFloor(next, next.player.x, next.player.z)) return false;
        int samples = Math.max(2, (int) Math.ceil(
                Math.hypot(next.player.x - source.player.x, next.player.z - source.player.z)
                        / SAMPLE_STEP));
        for (int i = 1; i < samples; i++) {
            double t = i / (double) samples;
            if (!isPhysicalFloor(source,
                    source.player.x + (next.player.x - source.player.x) * t,
                    source.player.z + (next.player.z - source.player.z) * t)) {
                return false;
            }
        }
        return true;
    }

    private boolean isPhysicalFloor(GameState state, double x, double z) {
        int row = (int) Math.floor(x);
        int col = (int) Math.floor(z);
        return row >= 0 && col >= 0
                && row < me.monstermazeai.maze.MazeModel.SIZE
                && col < me.monstermazeai.maze.MazeModel.SIZE
                && state.maze.isPhysicalFloor(row, col);
    }

    private boolean jumpOpportunity(GameState state, PlayerRoute route, double tx, double tz) {
        if (!state.player.grounded) return false;
        if (edgeDistance(state) < 0.40 && outwardVelocity(state) > 0.02) return false;

        // Jumper's five charges are strategic resources. A normal route does
        // not justify spending one. Reserve them for an imminent deadline or
        // a genuine recovery/emergency situation; once the charges are gone,
        // ordinary jump-spam is enabled for every kit.
        if (state.kit == me.monstermazeai.kit.Kit.JUMPER && state.player.jumpCharges > 0) {
            boolean imminentDeadline = state.phaseTicksRemaining >= 0
                    && state.phaseTicksRemaining > 0
                    && state.phaseTicksRemaining <= 40
                    && Math.hypot(tx - state.player.x, tz - state.player.z) > 1.5;
            boolean recovery = state.player.recentMobHitUntilTick > state.tick
                    && edgeDistance(state) < 0.75;
            return imminentDeadline || recovery;
        }

        return Math.hypot(tx - state.player.x, tz - state.player.z) > 0.20
                && route.size() > 1;
    }

    private boolean safePredictedTrajectory(GameState source, GameState next) {
        if (!next.alive) return false;
        if (!safePosition(next, next.player.x, next.player.z)) return false;

        int samples = Math.max(2, (int) Math.ceil(
                Math.hypot(next.player.x - source.player.x, next.player.z - source.player.z)
                        / SAMPLE_STEP));
        for (int i = 1; i < samples; i++) {
            double t = i / (double) samples;
            double x = source.player.x + (next.player.x - source.player.x) * t;
            double z = source.player.z + (next.player.z - source.player.z) * t;
            if (!safePosition(source, x, z)) return false;
        }
        return true;
    }

    private boolean safeSegment(GameState state, double x0, double z0, double x1, double z1) {
        int samples = Math.max(2, (int) Math.ceil(
                Math.hypot(x1 - x0, z1 - z0) / SAMPLE_STEP));
        for (int i = 0; i <= samples; i++) {
            double t = i / (double) samples;
            if (!safePosition(state, x0 + (x1 - x0) * t, z0 + (z1 - z0) * t)) return false;
        }
        return true;
    }

    private boolean safePosition(GameState state, double x, double z) {
        int row = (int) Math.floor(x);
        int col = (int) Math.floor(z);
        if (row < 0 || col < 0
                || row >= me.monstermazeai.maze.MazeModel.SIZE
                || col >= me.monstermazeai.maze.MazeModel.SIZE) return false;
        if (!state.maze.isPhysicalFloor(row, col)) return false;
        double margin = Math.min(Math.min(x - row, row + 1.0 - x),
                Math.min(z - col, col + 1.0 - z));
        return margin >= EDGE_MARGIN || boundaryHasFloor(state, x, z, row, col);
    }

    private boolean boundaryHasFloor(GameState state, double x, double z, int row, int col) {
        final double eps = 1.0E-6;
        if (x - row < EDGE_MARGIN && row > 0)
            return state.maze.isPhysicalFloor(row - 1, col);
        if (row + 1.0 - x < EDGE_MARGIN && row + 1 < me.monstermazeai.maze.MazeModel.SIZE)
            return state.maze.isPhysicalFloor(row + 1, col);
        if (z - col < EDGE_MARGIN && col > 0)
            return state.maze.isPhysicalFloor(row, col - 1);
        if (col + 1.0 - z < eps && col + 1 < me.monstermazeai.maze.MazeModel.SIZE)
            return state.maze.isPhysicalFloor(row, col + 1);
        if (x - row < EDGE_MARGIN && row > 0)
            return state.maze.isPhysicalFloor(row - 1, col);
        if (row + 1.0 - x < EDGE_MARGIN && row + 1 < me.monstermazeai.maze.MazeModel.SIZE)
            return state.maze.isPhysicalFloor(row + 1, col);
        if (z - col < EDGE_MARGIN && col > 0)
            return state.maze.isPhysicalFloor(row, col - 1);
        return col + 1 < me.monstermazeai.maze.MazeModel.SIZE
                && z >= col + (1.0 - EDGE_MARGIN)
                && state.maze.isPhysicalFloor(row, col + 1);
    }

    private double edgeDistance(GameState state) {
        int row = (int) Math.floor(state.player.x);
        int col = (int) Math.floor(state.player.z);
        if (!state.maze.isPhysicalFloor(row, col)) return 0.0;
        return Math.min(Math.min(state.player.x - row, row + 1.0 - state.player.x),
                Math.min(state.player.z - col, col + 1.0 - state.player.z));
    }

    private double outwardVelocity(GameState state) {
        int row = (int) Math.floor(state.player.x);
        int col = (int) Math.floor(state.player.z);
        double nx = state.player.x - (row + 0.5);
        double nz = state.player.z - (col + 0.5);
        double len = Math.hypot(nx, nz);
        return len < 1.0E-9 ? 0.0 : (state.player.vx * nx + state.player.vz * nz) / len;
    }

    private double distanceToRoute(double x, double z, PlayerRoute route) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < route.size(); i++) {
            best = Math.min(best, Math.hypot(x - route.targetX(i), z - route.targetZ(i)));
        }
        return best;
    }

    private double edgePenalty(GameState state) {
        int row = (int) Math.floor(state.player.x);
        int col = (int) Math.floor(state.player.z);
        if (!state.maze.isPhysicalFloor(row, col)) return 100.0;
        double edge = Math.min(Math.min(state.player.x - row, row + 1.0 - state.player.x),
                Math.min(state.player.z - col, col + 1.0 - state.player.z));
        return Math.max(0.0, EDGE_MARGIN - edge);
    }

    private static int signInput(double value) {
        if (value > 0.25) return 1;
        if (value < -0.25) return -1;
        return 0;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double wrap(double value) {
        while (value >= 180.0) value -= 360.0;
        while (value < -180.0) value += 360.0;
        return value;
    }

    private static String describe(Action a) {
        return "f=" + a.forward() + ",s=" + a.strafe()
                + ",jump=" + a.jump() + ",sprint=" + a.sprint()
                + ",yawDelta=" + a.yawDelta();
    }
}
