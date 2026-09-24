package me.monstermazeai.planner;

import me.monstermazeai.ability.AbilityDecision;
import me.monstermazeai.ability.AbilityUseGate;
import me.monstermazeai.game.GameState;
import me.monstermazeai.player.Action;

/**
 * Stateful safety wrapper around the one-tick objective controller.
 *
 * The wrapper never executes a cached multi-tick plan. It uses the newest
 * observation to replan, rejects stale ticks, detects lack of displacement,
 * and requests a jump on a persistent stall. Any invalid transition fails
 * closed to IDLE.
 */
public final class RobustLiveController {
    private static final int STUCK_TICKS = 8;
    private static final double MIN_PROGRESS_SQ = 0.03 * 0.03;

    private final LiveObjectiveController objective;
    private final AbilityUseGate abilityGate = new AbilityUseGate();

    private long lastTick = Long.MIN_VALUE;
    private double lastX;
    private double lastZ;
    private int stuckTicks;

    public RobustLiveController(LiveObjectiveController objective) {
        if (objective == null) throw new IllegalArgumentException("objective");
        this.objective = objective;
    }

    public Action nextAction(GameState state, boolean allowJump) {
        if (!validLiveState(state)) {
            reset();
            return Action.IDLE;
        }
        if (state.tick <= lastTick) return Action.IDLE;

        boolean sameObjective = state.activePadRow >= 0 && state.activePadColumn >= 0;
        double dx = state.player.x - lastX;
        double dz = state.player.z - lastZ;
        if (lastTick != Long.MIN_VALUE && sameObjective
                && dx * dx + dz * dz < MIN_PROGRESS_SQ) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }

        lastTick = state.tick;
        lastX = state.player.x;
        lastZ = state.player.z;

        Action action = objective.nextAction(state, allowJump);
        if (action == Action.IDLE) {
            stuckTicks = 0;
            return Action.IDLE;
        }

        if (stuckTicks >= STUCK_TICKS && allowJump && state.player.grounded) {
            stuckTicks = 0;
            return new Action(action.forward(), action.strafe(), true,
                    action.sprint(), action.yawDelta(), action.useAbility());
        }

        if (AbilityDecision.shouldUse(state) && abilityGate.allow(state)) {
            abilityGate.record(state);
            return new Action(action.forward(), action.strafe(), action.jump(),
                    action.sprint(), action.yawDelta(), true);
        }

        return action;
    }

    public void reset() {
        lastTick = Long.MIN_VALUE;
        stuckTicks = 0;
        lastX = lastZ = 0.0;
    }

    private static boolean validLiveState(GameState s) {
        return s != null && s.inMonsterMaze && s.alive && !s.completed
                && s.maze != null && s.phaseTicksRemaining > 0
                && s.activePadRow >= 0 && s.activePadColumn >= 0;
    }
}
