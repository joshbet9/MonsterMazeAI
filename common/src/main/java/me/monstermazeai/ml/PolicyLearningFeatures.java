package me.monstermazeai.ml;

import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.player.Action;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixed-size state/action vector for long-horizon policy learning.
 *
 * The model predicts a scalar return for an executable action from the current
 * source-faithful game state. Mechanics remain entirely outside this feature
 * layer.
 */
public final class PolicyLearningFeatures {
    public static final String[] NAMES = {
            "stage_norm",
            "health_ratio",
            "horizontal_speed",
            "forward_speed",
            "lateral_speed",
            "vertical_speed",
            "grounded",
            "phase_ticks_norm",
            "ability_charges_norm",
            "ability_active_norm",
            "pad_distance_norm",
            "pad_direction_cos",
            "pad_direction_sin",
            "old_pad_count_norm",
            "preview_pad_distance_norm",
            "local_floor_north",
            "local_floor_south",
            "local_floor_east",
            "local_floor_west",
            "local_floor_northeast",
            "local_floor_northwest",
            "local_floor_southeast",
            "local_floor_southwest",
            "mode_speed",
            "mode_modern",
            "kit_jumper",
            "kit_maverick",
            "kit_slowballer",
            "kit_repulsor",
            "kit_body_builder",
            "monster_count_12_norm",
            "monster_count_20_norm",
            "nearest_monster_distance_norm",
            "nearest_monster_closing_norm",
            "nearest_monster_forward_norm",
            "nearest_monster_lateral_norm",
            "max_monster_closing_norm",
            "min_time_to_contact_norm",
            "action_forward",
            "action_strafe",
            "action_jump",
            "action_sprint",
            "action_yaw_delta",
            "action_ability"
    };

    private PolicyLearningFeatures() {}

    public static double[] extract(GameState state, Action action) {
        if (state == null || action == null) {
            throw new IllegalArgumentException("state and action are required");
        }

        double[] f = new double[NAMES.length];
        f[0] = clamp01(state.stage / 100.0);

        double maxHealth = Math.max(1.0, state.player.maxHealth);
        f[1] = clamp01(state.player.health / maxHealth);

        double yaw = Math.toRadians(state.player.yaw);
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double strafeX = Math.cos(yaw);
        double strafeZ = Math.sin(yaw);

        double horizontalSpeed = Math.hypot(state.player.vx, state.player.vz);
        f[2] = clamp(horizontalSpeed / 0.60, 0.0, 3.0);
        f[3] = clamp((state.player.vx * forwardX + state.player.vz * forwardZ) / 0.60, -3.0, 3.0);
        f[4] = clamp((state.player.vx * strafeX + state.player.vz * strafeZ) / 0.60, -3.0, 3.0);
        f[5] = clamp(state.player.vy / 0.50, -4.0, 4.0);
        f[6] = state.player.grounded ? 1.0 : 0.0;
        f[7] = clamp(state.phaseTicksRemaining / 1200.0, 0.0, 2.0);
        f[8] = clamp(state.ability.charges / 3.0, 0.0, 1.0);
        f[9] = clamp(Math.max(0, state.ability.activeUntilTick - state.tick) / 600.0, 0.0, 1.0);

        if (state.activePadRow >= 0 && state.activePadColumn >= 0) {
            double dx = state.activePadRow + 0.5 - state.player.x;
            double dz = state.activePadColumn + 0.5 - state.player.z;
            double distance = Math.hypot(dx, dz);
            f[10] = clamp(distance / 100.0, 0.0, 2.0);
            if (distance > 1.0E-6) {
                f[11] = dx / distance * forwardX + dz / distance * forwardZ;
                f[12] = dx / distance * strafeX + dz / distance * strafeZ;
            }
        }

        f[13] = clamp(state.oldPads.size() / 10.0, 0.0, 1.0);

        if (state.previewPadRow >= 0 && state.previewPadColumn >= 0) {
            double dx = state.previewPadRow + 0.5 - state.player.x;
            double dz = state.previewPadColumn + 0.5 - state.player.z;
                f[14] = clamp(Math.hypot(dx, dz) / 100.0, 0.0, 2.0);
        }

        int playerRow = (int) Math.floor(state.player.x);
        int playerColumn = (int) Math.floor(state.player.z);
        int[][] offsets = {
                {-1, 0}, {1, 0}, {0, 1}, {0, -1},
                {-1, 1}, {-1, -1}, {1, 1}, {1, -1}
        };
        for (int i = 0; i < offsets.length; i++) {
            int r = playerRow + offsets[i][0];
            int col = playerColumn + offsets[i][1];
            boolean floor = state.maze != null
                    && r >= 0 && r < me.monstermazeai.maze.MazeModel.SIZE
                    && col >= 0 && col < me.monstermazeai.maze.MazeModel.SIZE
                    && state.maze.isPhysicalFloor(r, col);
            f[15 + i] = floor ? 1.0 : 0.0;
        }

        f[23] = state.mode.name().equals("SPEED") ? 1.0 : 0.0;
        f[24] = state.mode.name().equals("MODERN") ? 1.0 : 0.0;
        f[25] = kit(state, Kit.JUMPER);
        f[26] = kit(state, Kit.MAVERICK);
        f[27] = kit(state, Kit.SLOWBALLER);
        f[28] = kit(state, Kit.REPULSOR);
        f[29] = kit(state, Kit.BODY_BUILDER);

        int within12 = 0;
        int within20 = 0;
        double nearest = Double.POSITIVE_INFINITY;
        double nearestClosing = 0.0;
        double nearestForward = 0.0;
        double nearestLateral = 0.0;
        double maxClosing = 0.0;
        double minTtc = Double.POSITIVE_INFINITY;

        for (MonsterState monster : state.monsters) {
            if (monster.removed || monster.launched(state.tick) || monster.frozen(state.tick)) continue;
            double dx = monster.x - state.player.x;
            double dz = monster.z - state.player.z;
            double distance = Math.hypot(dx, dz);
            if (distance <= 12.0) within12++;
            if (distance <= 20.0) within20++;

            double closing = 0.0;
            if (distance > 1.0E-6) {
                closing = (monster.vx * -dx + monster.vz * -dz) / distance;
            }
            if (distance < nearest) {
                nearest = distance;
                nearestClosing = closing;
                nearestForward = dx * forwardX + dz * forwardZ;
                nearestLateral = dx * strafeX + dz * strafeZ;
            }
            maxClosing = Math.max(maxClosing, Math.max(0.0, closing));
            if (closing > 1.0E-6) {
                minTtc = Math.min(minTtc, distance / closing);
            }
        }

        f[30] = clamp(within12 / 30.0, 0.0, 1.0);
        f[31] = clamp(within20 / 80.0, 0.0, 1.0);
        f[32] = Double.isFinite(nearest) ? clamp(nearest / 20.0, 0.0, 2.0) : 2.0;
        f[33] = clamp(nearestClosing / 0.60, -3.0, 3.0);
        f[34] = clamp(nearestForward / 20.0, -2.0, 2.0);
        f[35] = clamp(nearestLateral / 20.0, -2.0, 2.0);
        f[36] = clamp(maxClosing / 0.60, 0.0, 3.0);
        f[37] = Double.isFinite(minTtc) ? clamp(minTtc / 20.0, 0.0, 2.0) : 2.0;

        f[38] = action.forward();
        f[39] = action.strafe();
        f[40] = action.jump() ? 1.0 : 0.0;
        f[41] = action.sprint() ? 1.0 : 0.0;
        f[42] = clamp(action.yawDelta() / 30.0, -1.0, 1.0);
        f[43] = action.useAbility() ? 1.0 : 0.0;
        return f;
    }

    private static double kit(GameState state, Kit kit) {
        return state.kit == kit ? 1.0 : 0.0;
    }

    public static double reward(GameState before, GameState after) {
        if (before == null || after == null) throw new IllegalArgumentException("states");

        double reward = 0.02; // surviving another source tick has small positive value

        int stageDelta = after.stage - before.stage;
        if (stageDelta > 0) reward += stageDelta * 100.0;

        // Do not compare against a newly promoted pad. That coordinate jump
        // is an environment transition, not player progress.
        if (after.stage == before.stage
                && before.activePadRow >= 0 && before.activePadColumn >= 0
                && before.activePadRow == after.activePadRow
                && before.activePadColumn == after.activePadColumn) {
            double beforeDx = before.activePadRow + 0.5 - before.player.x;
            double beforeDz = before.activePadColumn + 0.5 - before.player.z;
            double afterDx = after.activePadRow + 0.5 - after.player.x;
            double afterDz = after.activePadColumn + 0.5 - after.player.z;
            double beforeDistance = Math.hypot(beforeDx, beforeDz);
            double afterDistance = Math.hypot(afterDx, afterDz);
            reward += clamp(beforeDistance - afterDistance, -1.0, 1.0) * 0.5;
        }

        double damageDelta = Math.max(0.0, after.player.damageTaken - before.player.damageTaken);
        reward -= damageDelta * 1.5;

        if (after.player.y < GameState.PATH_Y - 0.05) {
            reward -= 2.0;
        }
        if (!after.alive) {
            reward -= 100.0;
        }

        return reward;
    }

    public static boolean done(GameState state) {
        return state == null || !state.alive || state.completed;
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static double clamp01(double value) {
        return clamp(value, 0.0, 1.0);
    }
}
