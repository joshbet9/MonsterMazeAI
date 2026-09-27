package me.monstermazeai.physics;

import me.monstermazeai.game.GameState;
import me.monstermazeai.player.Action;
import me.monstermazeai.player.PlayerState;

/**
 * Conservative planning model for the observed 1.8 "speed into a mob" case.
 *
 * The authoritative MonsterManager bump still supplies the source knockback.
 * This model only changes the tactical simulation when a high-speed forward
 * approach makes a contact vulnerable to becoming a horizontal slide. In that
 * case the planner evaluates the contact with no guaranteed vertical recovery.
 *
 * This is deliberately conservative: it is not presented as a replacement
 * for the source UtilAction.velocity() implementation.
 */
public final class SpeedContactModel {
    private static final double MIN_SPEEDING_SPEED = 0.10;
    private static final double DIRECT_APPROACH_DOT = 0.65;
    private static final double LOOKAHEAD_TICKS = 3.0;

    private SpeedContactModel() {}

    public static void applyConservativeSlideOutcome(
            GameState before, GameState after, Action action, boolean wasHit) {
        if (!wasHit || before.maze == null) return;
        if (!isDirectSpeedApproach(before.player, after.player, action)) return;
        if (!projectsOffPhysicalFloor(after)) return;

        // The source bump has already supplied horizontal knockback. The
        // conservative branch removes the vertical recovery rather than
        // inventing a new horizontal force.
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
        double x = state.player.x + state.player.vx * LOOKAHEAD_TICKS;
        double z = state.player.z + state.player.vz * LOOKAHEAD_TICKS;
        int row = (int) Math.floor(x);
        int col = (int) Math.floor(z);
        return !state.maze.isPhysicalFloor(row, col);
    }
}
