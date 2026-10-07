package me.monstermazeai.minecraft.v18;

import me.monstermazeai.adapter.LegacyAction;
import org.junit.Test;

import static org.junit.Assert.*;

public class Minecraft18ActionExecutorTest {
    @Test
    public void legacyActionClampsMovementInputs() {
        LegacyAction action = new LegacyAction(2.0, -2.0, true, true, 90.0f, true);
        assertEquals(1.0, action.forward, 0.0);
        assertEquals(-1.0, action.strafe, 0.0);
        assertTrue(action.jump);
        assertTrue(action.sprint);
        assertEquals(90.0f, action.yawDelta, 0.0f);
        assertTrue(action.useAbility);
    }

    @Test
    public void idleIsAllReleasedIntent() {
        LegacyAction action = LegacyAction.IDLE;
        assertEquals(0.0, action.forward, 0.0);
        assertEquals(0.0, action.strafe, 0.0);
        assertFalse(action.jump);
        assertFalse(action.sprint);
        assertEquals(0.0f, action.yawDelta, 0.0f);
        assertFalse(action.useAbility);
    }
    @Test
    public void jumpInputExpiresWithTheCommandTick() {
        Minecraft18ActionExecutor executor =
                new Minecraft18ActionExecutor();
        executor.setAiEnabled(true);

        executor.apply(new LegacyAction(1.0, 0.0, true, true, 0.0F, false), 100L);

        LegacyAction sameTick = executor.currentAction(100L);
        LegacyAction nextTick = executor.currentAction(101L);

        assertTrue(sameTick.jump);
        assertFalse(nextTick.jump);
    }

    @Test
    public void expiredMovementCannotLeakIntoTheFollowingTick() {
        Minecraft18ActionExecutor executor =
                new Minecraft18ActionExecutor();
        executor.setAiEnabled(true);

        executor.apply(new LegacyAction(1.0, 0.0, false, true, 0.0F, false), 100L);

        LegacyAction expired = executor.currentAction(101L);

        assertEquals(0.0, expired.forward, 0.0);
        assertEquals(0.0, expired.strafe, 0.0);
        assertFalse(expired.sprint);
    }

}
