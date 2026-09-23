package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.player.Action;

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
        BeamSearchPlanner.Plan plan =
                planner.plan(state, targetX, targetZ, allowJump);
        Action[] actions = plan.sequence().actions();
        return actions.length == 0 ? Action.IDLE : actions[0];
    }
}
