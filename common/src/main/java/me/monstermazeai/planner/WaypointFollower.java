package me.monstermazeai.planner;

import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.game.GameState;
import me.monstermazeai.player.Action;

/**
 * Low-level route follower for the open-floor Monster Maze geometry.
 *
 * It does not perform block collision, wall hugging, or Minecraft path
 * collision. The maze route is navigation guidance only; LegacyMazePhysics
 * remains responsible for physical movement.
 */
public final class WaypointFollower {
    private final double waypointTolerance;
    private int waypointIndex;

    public WaypointFollower() {
        this(0.18);
    }

    public WaypointFollower(double waypointTolerance) {
        if (waypointTolerance <= 0.0) throw new IllegalArgumentException();
        this.waypointTolerance = waypointTolerance;
    }

    public void reset() {
        waypointIndex = 0;
    }

    public int waypointIndex() {
        return waypointIndex;
    }

    public boolean finished(PlayerRoute route, GameState state) {
        waypointIndex = route.nextWaypoint(
                state.player.x, state.player.z, waypointIndex, waypointTolerance);
        return waypointIndex == route.size() - 1
                && route.reached(state.player.x, state.player.z, waypointTolerance);
    }

    public Action nextAction(PlayerRoute route, GameState state, boolean allowJump) {
        waypointIndex = route.nextWaypoint(
                state.player.x, state.player.z, waypointIndex, waypointTolerance);

        if (waypointIndex >= route.size() - 1
                && route.reached(state.player.x, state.player.z, waypointTolerance)) {
            return Action.IDLE;
        }

        double tx = route.targetX(waypointIndex);
        double tz = route.targetZ(waypointIndex);
        double dx = tx - state.player.x;
        double dz = tz - state.player.z;

        if (Math.hypot(dx, dz) <= waypointTolerance && waypointIndex < route.size() - 1) {
            waypointIndex++;
            tx = route.targetX(waypointIndex);
            tz = route.targetZ(waypointIndex);
        }

        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float delta = normalise(desiredYaw - state.player.yaw);

        // Keep the controller conservative at corners. The physics model can
        // carry velocity through a turn; the route follower only changes
        // heading and never teleports or snaps position.
        return new Action(1.0, 0.0, allowJump && !state.player.grounded, true, delta, false);
    }

    private float normalise(float angle) {
        while (angle >= 180.0F) angle -= 360.0F;
        while (angle < -180.0F) angle += 360.0F;
        return angle;
    }
}
