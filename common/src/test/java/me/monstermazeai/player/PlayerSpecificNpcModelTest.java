package me.monstermazeai.player;

import me.monstermazeai.game.GameState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerSpecificNpcModelTest {
    private PlayerBehaviorProfile profile(double sprint, double jump, double forward, double strafe) {
        return new PlayerBehaviorProfile(100, 0.2, sprint, jump, 0.2, 2, 0, 0, forward, strafe);
    }

    @Test void preservesNavigationAndAppliesSprintPreference() {
        PlayerSpecificNpcModel model = new PlayerSpecificNpcModel(profile(1,0,1,0));
        GameState state = new GameState();
        Action shaped = model.shape(state, new Action(1,0,false,false,3,false));
        assertEquals(1, shaped.forward(), 1e-9);
        assertTrue(shaped.sprint());
        assertEquals(3, shaped.yawDelta(), 1e-9);
    }

    @Test void appliesMeasuredStrafeAndDeterministicJumpPreference() {
        PlayerSpecificNpcModel model = new PlayerSpecificNpcModel(profile(0,0.25,1,0.8));
        GameState state = new GameState();
        state.tick=4;
        state.player.grounded=true;
        Action shaped = model.shape(state, new Action(1,0,false,false,0,false));
        assertEquals(0.2, shaped.strafe(), 1e-9);
        assertTrue(shaped.jump());
    }

    @Test void emptyProfileIsTransparent() {
        PlayerSpecificNpcModel model = new PlayerSpecificNpcModel(
                new PlayerBehaviorTracker().profile());
        Action base = new Action(1,0,false,true,4,true);
        assertEquals(base, model.shape(new GameState(), base));
    }
}
