package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.player.Action;

import java.util.Arrays;

/**
 * Executes only the first action of a freshly planned trajectory.
 *
 * The live adapter can call this once per client/server tick. The next call
 * replans from the observed GameState, so prediction errors do not accumulate
 * into a fixed replay.
 */
public final class RecedingHorizonController {
    private final BeamSearchPlanner planner;

    public RecedingHorizonController(BeamSearchPlanner planner) {
        this.planner = planner;
    }

    public Action nextAction(GameState state, double targetX, double targetZ,
                             boolean allowJump) {
        Action[] actions = nextActions(state, targetX, targetZ, allowJump, 1);
        return actions.length == 0 ? Action.IDLE : actions[0];
    }

    /**
     * Returns a short execution window from one fresh plan. A live adapter can
     * execute this window, observe the actual game, then call nextActions again.
     * The controller never caches the predicted state between windows.
     */
    public Action[] nextActions(GameState state, double targetX, double targetZ,
                                boolean allowJump, int executionTicks) {
        if (executionTicks < 1) throw new IllegalArgumentException("executionTicks must be positive");
        BeamSearchPlanner.Plan plan =
                planner.plan(state, targetX, targetZ, allowJump);
        Action[] actions = plan.sequence().actions();
        if (actions.length == 0) return new Action[]{Action.IDLE};
        return Arrays.copyOf(actions, Math.min(executionTicks, actions.length));
    }
}
