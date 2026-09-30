package me.monstermazeai.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiProfileTest {
    @Test
    void baselinePreservesCurrentFourTickSpeedingCadence() {
        assertEquals(4L, AiProfile.BASELINE.attributes.nonJumperJumpCadenceTicks());
    }

    @Test
    void maxSpeedMonotonicallyIncreasesSpeedingInputRate() {
        long slow = new AiAttributes(0.0, 0.5, 0.5, 0.5).nonJumperJumpCadenceTicks();
        long medium = new AiAttributes(0.5, 0.5, 0.5, 0.5).nonJumperJumpCadenceTicks();
        long fast = new AiAttributes(1.0, 0.5, 0.5, 0.5).nonJumperJumpCadenceTicks();

        assertTrue(slow >= medium);
        assertTrue(medium >= fast);
        assertTrue(fast >= 1L && slow <= 8L);
    }

    @Test
    void attributesAndTendenciesRejectOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new AiAttributes(1.01, 0.5, 0.5, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> new AiTendencies(AiTendencies.DirectionChangeType.MIXED,
                        0.5, -0.01, 0.5, 0.5, 0.5, 0.5));
    }

    @Test
    void profileRequiresBothComponents() {
        assertThrows(IllegalArgumentException.class,
                () -> new AiProfile(null, AiTendencies.BASELINE));
        assertThrows(IllegalArgumentException.class,
                () -> new AiProfile(AiAttributes.BASELINE, null));
    }
}
