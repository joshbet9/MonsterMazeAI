package me.monstermazeai.runtime;

import me.monstermazeai.game.GameState;
import me.monstermazeai.player.Action;
import me.monstermazeai.player.PlayerSpecificNpcModel;

/**
 * Autonomous Monster Maze agent with a player-specific control style.
 *
 * Navigation, monster avoidance and ability decisions remain owned by the
 * autonomous base agent. This layer only emulates the measured player's
 * control tendencies.
 */
public final class PlayerSpecificNpcAgent {
    private final AutonomousMonsterMazeAgent base;
    private final PlayerSpecificNpcModel model;

    public PlayerSpecificNpcAgent(AutonomousMonsterMazeAgent base, PlayerSpecificNpcModel model) {
        if (base == null || model == null) throw new IllegalArgumentException("base/model");
        this.base = base;
        this.model = model;
    }

    public Action decide(GameState state, boolean allowJump) {
        Action action = base.decide(state, allowJump);
        if (action == Action.IDLE) return action;
        return model.shape(state, action);
    }

    public void reset() {
        base.reset();
    }
}
