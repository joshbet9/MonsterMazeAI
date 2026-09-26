package me.monstermazeai.planner;

import me.monstermazeai.ability.AbilityDecision;
import me.monstermazeai.ability.AbilityUseGate;
import me.monstermazeai.game.GameState;
import me.monstermazeai.player.Action;

/**
 * Stateful safety wrapper around the one-tick objective controller.
 *
 * Phase time is intentionally not part of the basic live-state validity gate;
 * the observed active pad remains actionable even when the timer is zero or
 * temporarily unavailable. The planner can use a positive timer as a deadline
 * when one is available.
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
    private String lastDecisionDetail = "UNSET";

    public RobustLiveController(LiveObjectiveController objective) {
        if (objective == null) throw new IllegalArgumentException("objective");
        this.objective = objective;
    }

    public Action nextAction(GameState state, boolean allowJump) {
        if (!validLiveState(state)) {
            lastDecisionDetail = "INVALID_LIVE_STATE";
            reset();
            return Action.IDLE;
        }
        if (state.tick <= lastTick) {
            lastDecisionDetail = "STALE_TICK stateTick=" + state.tick + " lastTick=" + lastTick;
            return Action.IDLE;
        }

        boolean sameObjective = state.activePadRow >= 0 && state.activePadColumn >= 0;
        double dx = state.player.x - lastX;
        double dz = state.player.z - lastZ;
        double displacementSq = dx * dx + dz * dz;
        if (lastTick != Long.MIN_VALUE && sameObjective && displacementSq < MIN_PROGRESS_SQ) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }

        lastTick = state.tick;
        lastX = state.player.x;
        lastZ = state.player.z;

        Action action = objective.nextAction(state, allowJump);
        if (action == Action.IDLE) {
            lastDecisionDetail = "OBJECTIVE_IDLE reason=" + objective.lastDecisionReason()
                    + " detail=" + objective.lastDecisionDetail()
                    + " stuckTicks=" + stuckTicks;
            stuckTicks = 0;
            return Action.IDLE;
        }

        if (stuckTicks >= STUCK_TICKS && allowJump && state.player.grounded) {
            stuckTicks = 0;
            Action jump = new Action(action.forward(), action.strafe(), true,
                    action.sprint(), action.yawDelta(), action.useAbility());
            lastDecisionDetail = "FORCED_JUMP objective=" + objective.lastDecisionReason()
                    + " stuckTicks=" + STUCK_TICKS
                    + " base=" + describe(action)
                    + " output=" + describe(jump);
            return jump;
        }

        if (AbilityDecision.shouldUse(state) && abilityGate.allow(state)) {
            abilityGate.record(state);
            Action ability = new Action(action.forward(), action.strafe(), action.jump(),
                    action.sprint(), action.yawDelta(), true);
            lastDecisionDetail = "ABILITY_ADD objective=" + objective.lastDecisionReason()
                    + " base=" + describe(action) + " output=" + describe(ability);
            return ability;
        }

        lastDecisionDetail = "PASS objective=" + objective.lastDecisionReason()
                + " detail=" + objective.lastDecisionDetail()
                + " stuckTicks=" + stuckTicks
                + " displacement=" + Math.sqrt(displacementSq)
                + " output=" + describe(action);
        return action;
    }

    public String lastDecisionDetail() { return lastDecisionDetail; }

    public void reset() {
        lastTick = Long.MIN_VALUE;
        stuckTicks = 0;
        lastX = lastZ = 0.0;
        lastDecisionDetail = "RESET";
    }

    private static boolean validLiveState(GameState s) {
        return s != null && s.inMonsterMaze && s.alive && !s.completed
                && s.maze != null
                && s.activePadRow >= 0 && s.activePadColumn >= 0;
    }

    private static String describe(Action action) {
        return "f=" + action.forward() + ",s=" + action.strafe()
                + ",jump=" + action.jump() + ",sprint=" + action.sprint()
                + ",yawDelta=" + action.yawDelta()
                + ",ability=" + action.useAbility();
    }
}
