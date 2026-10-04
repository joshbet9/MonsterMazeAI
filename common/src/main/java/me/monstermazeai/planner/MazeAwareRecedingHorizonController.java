package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.player.Action;
import me.monstermazeai.player.AiProfile;

/**
 * Compatibility facade for the live movement layer.
 *
 * Live control uses the deterministic shortest-route controller. BeamSearchPlanner
 * and the previous tactical controllers remain available to the offline stack,
 * but they are deliberately out of the live decision loop.
 */
public final class MazeAwareRecedingHorizonController {
    private final DeterministicMazeController deterministicMovement;
    private final int executionTicks;
    private String lastDecisionDetail = "UNSET";

    public MazeAwareRecedingHorizonController(BeamSearchPlanner planner, int executionTicks) {
        this(executionTicks);
    }

    public MazeAwareRecedingHorizonController(BeamSearchPlanner planner,
                                               int executionTicks,
                                               double waypointTolerance) {
        this(executionTicks);
    }

    public MazeAwareRecedingHorizonController(int executionTicks) {
        this(executionTicks, AiProfile.BASELINE);
    }

    public MazeAwareRecedingHorizonController(int executionTicks, AiProfile profile) {
        if (executionTicks < 1) throw new IllegalArgumentException();
        if (profile == null) throw new IllegalArgumentException("profile");
        this.executionTicks = executionTicks;
        this.deterministicMovement = new DeterministicMazeController(profile);
    }

    public String lastDecisionDetail() {
        return lastDecisionDetail;
    }

    public Action[] nextActions(GameState state, Cell goal, boolean allowJump) {
        return nextActions(state, goal, allowJump, 0);
    }

    public Action[] nextActions(GameState state, Cell goal, boolean allowJump, int regionRadius) {
        if (state == null || goal == null) {
            lastDecisionDetail = "INVALID_INPUT";
            return new Action[]{Action.IDLE};
        }

        Action first = deterministicMovement.nextAction(state, goal, allowJump, regionRadius);
        lastDecisionDetail = deterministicMovement.lastDecisionDetail();

        /*
         * The live adapter consumes only the first action. Keep the public
         * multi-action API for existing callers, but never repeat a movement
         * command into the future: the next observation supplies fresh state.
         */
        return new Action[]{first};
    }
}
