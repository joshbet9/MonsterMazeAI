package me.monstermazeai.runtime;

import me.monstermazeai.game.GameState;
import me.monstermazeai.player.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerSpecificNpcAgentTest {
    @Test void stylesBaseDecisionWithoutReplacingIt() {
        PlayerBehaviorProfile p = new PlayerBehaviorProfile(10,0.2,1,0,0,0,0,0,1,0);
        PlayerSpecificNpcAgent agent = new PlayerSpecificNpcAgent(
                (state, allowJump) -> new Action(1,0,false,false,3,false),
                new PlayerSpecificNpcModel(p));
        Action action = agent.decide(new GameState(), true);
        assertEquals(1, action.forward(), 1e-9);
        assertTrue(action.sprint());
        assertEquals(3, action.yawDelta(), 1e-9);
    }

    @Test void idleFromBaseRemainsIdle() {
        PlayerSpecificNpcAgent agent = new PlayerSpecificNpcAgent(
                (state, allowJump) -> Action.IDLE,
                new PlayerSpecificNpcModel(new PlayerBehaviorTracker().profile()));
        assertEquals(Action.IDLE, agent.decide(new GameState(), true));
    }
}
