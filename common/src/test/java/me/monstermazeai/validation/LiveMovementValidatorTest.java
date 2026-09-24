package me.monstermazeai.validation;

import me.monstermazeai.adapter.LegacyAction;
import me.monstermazeai.adapter.LegacyWorldObservation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LiveMovementValidatorTest {
    @Test
    void acceptsFiniteMovementAndCountsCommandedMotion() {
        LiveMovementValidator validator = new LiveMovementValidator();
        LegacyWorldObservation a = observation(10L, 0.0, 64.0, 0.0, true);
        LegacyWorldObservation b = observation(11L, 0.25, 64.0, 0.0, true);
        validator.observe(a, new LegacyAction(1.0, 0.0, false, true, 0.0f, false));
        validator.observe(b, LegacyAction.IDLE);
        assertTrue(validator.healthy());
        assertEquals(1L, validator.samples());
        assertEquals(1L, validator.movingSamples());
    }

    @Test
    void rejectsNonFiniteOrExcessiveLiveDisplacement() {
        LiveMovementValidator validator = new LiveMovementValidator();
        validator.observe(observation(1L, 0.0, 64.0, 0.0, true), LegacyAction.IDLE);
        validator.observe(observation(2L, 2.0, 64.0, 0.0, true), LegacyAction.IDLE);
        assertFalse(validator.healthy());
        assertEquals(1L, validator.invalidSamples());
    }

    @Test
    void tracksJumpAndAbilityCommands() {
        LiveMovementValidator validator = new LiveMovementValidator();
        LegacyWorldObservation a = observation(20L, 0.0, 64.0, 0.0, true);
        LegacyWorldObservation b = observation(21L, 0.0, 64.0, 0.1, false);
        validator.observe(a, new LegacyAction(0.0, 0.0, true, false, 0.0f, true));
        validator.observe(b, LegacyAction.IDLE);
        assertEquals(1L, validator.jumpSamples());
        assertEquals(1L, validator.abilitySamples());
    }

    private static LegacyWorldObservation observation(long tick, double x, double y, double z, boolean grounded) {
        return new LegacyWorldObservation(
                tick, true, true, 1, true, false, 1, 10, 1,
                new LegacyWorldObservation.Player(
                        x, y, z, 0.0, 0.0, 0.0, 0.0f, 0.0f, grounded, 20.0, 20.0),
                me.monstermazeai.kit.Kit.JUMPER, 1, 1,
                new LegacyWorldObservation.BlockPoint(0, 64, 0),
                new LegacyWorldObservation.Pad(50, 50, 1.0, false),
                openMaze(), java.util.Collections.emptyList(), "Monster Maze",
                java.util.Collections.singletonList("Stage"));
    }

    private static int[][] openMaze() {
        int[][] maze = new int[99][99];
        for (int r = 0; r < 99; r++) java.util.Arrays.fill(maze[r], 1);
        return maze;
    }
}
