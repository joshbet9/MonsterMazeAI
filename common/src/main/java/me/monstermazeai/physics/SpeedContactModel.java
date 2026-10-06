package me.monstermazeai.physics;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.player.Action;
import me.monstermazeai.player.PlayerState;

/**
 * Conservative planning model for the observed 1.8 "speed into a mob" case.
 *
 * MonsterManager/UtilAction remains authoritative for the actual bump. This
 * class only changes the tactical consequence when a high-speed forward
 * approach projects into non-floor space.
 */
public final class SpeedContactModel {
    private static final double MIN_SPEEDING_SPEED = 0.10;
    private static final double DIRECT_APPROACH_DOT = 0.65;
    private static final double LOOKAHEAD_TICKS = 3.0;

    private SpeedContactModel() {}

    public static void applyConservativeSlideOutcome(
            GameState before, GameState after, Action action, boolean wasHit) {
        if (before == null || before.maze == null) return;
        applyConservativeSlideOutcome(before.player, after, before.maze, action, wasHit);
    }

    /**
     * Allocation-free hot-path overload. Only the pre-contact player state is
     * copied by the caller; the immutable maze model is reused.
     */
    public static void applyConservativeSlideOutcome(
            PlayerState before, GameState after, MazeModel maze, Action action, boolean wasHit) {
        if (!wasHit || before == null || after == null || maze == null) return;
        if (!isDirectSpeedApproach(before, after.player, action)) return;
        if (!projectsOffPhysicalFloor(maze, after.player)) return;

        after.player.vy = 0.0;
        after.player.pendingAirborne = true;
        after.player.grounded = false;
    }

    static boolean isDirectSpeedApproach(PlayerState before, PlayerState after, Action action) {
        if (!action.sprint() || action.forward() <= 0.0) return false;

        double speed = Math.hypot(before.vx, before.vz);
        if (speed < MIN_SPEEDING_SPEED) return false;

        double kbSpeed = Math.hypot(after.vx, after.vz);
        if (kbSpeed < 1.0E-9) return false;

        double dot = (before.vx * after.vx + before.vz * after.vz) / (speed * kbSpeed);
        return dot >= DIRECT_APPROACH_DOT;
    }

    static boolean projectsOffPhysicalFloor(GameState state) {
        return state != null && state.maze != null
                && projectsOffPhysicalFloor(state.maze, state.player);
    }

    static boolean projectsOffPhysicalFloor(MazeModel maze, PlayerState player) {
        double x = player.x + player.vx * LOOKAHEAD_TICKS;
        double z = player.z + player.vz * LOOKAHEAD_TICKS;
        int row = (int) Math.floor(x);
        int col = (int) Math.floor(z);
        return !maze.isPhysicalFloor(row, col);
    }
}
