package me.monstermazeai.minecraft.v18;

import org.junit.Test;

import static org.junit.Assert.*;

public class FirstPadSpeedrunControllerTest {
    @Test
    public void proximityAloneCannotAdvanceRouteAtCorner() {
        assertFalse(FirstPadSpeedrunController.shouldCaptureRouteWaypoint(0.20D, 0.40D));
        assertFalse(FirstPadSpeedrunController.shouldCaptureRouteWaypoint(0.54D, 0.60D));
    }

    @Test
    public void meaningfulProgressStillCapturesNearWaypoint() {
        assertTrue(FirstPadSpeedrunController.shouldCaptureRouteWaypoint(0.55D, 0.60D));
        assertTrue(FirstPadSpeedrunController.shouldCaptureRouteWaypoint(0.80D, 0.90D));
        assertTrue(FirstPadSpeedrunController.shouldCaptureRouteWaypoint(0.90D, 2.0D));
    }
}
