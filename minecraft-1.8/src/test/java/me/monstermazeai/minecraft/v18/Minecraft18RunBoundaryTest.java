package me.monstermazeai.minecraft.v18;

import me.monstermazeai.adapter.LegacyWorldObservation;
import me.monstermazeai.kit.Kit;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class Minecraft18RunBoundaryTest {
    private static LegacyWorldObservation state(boolean inMaze, boolean alive, boolean completed) {
        return new LegacyWorldObservation(
                1L, inMaze, inMaze, 1, alive, completed, 1,
                10, 1,
                new LegacyWorldObservation.Player(
                        0.5D, 0.0D, 0.5D,
                        0.0D, 0.0D, 0.0D,
                        0.0F, 0.0F, true,
                        20.0D, 20.0D),
                Kit.JUMPER, 0, 0,
                null, null,
                new int[99][99],
                new boolean[99][99],
                Collections.<LegacyWorldObservation.Monster>emptyList(),
                "", Collections.<String>emptyList());
    }

    @Test
    public void startsOnlyInsideMonsterMaze() {
        assertTrue(Minecraft18RunBoundary.isGameStart(state(true, true, false)));
        assertFalse(Minecraft18RunBoundary.isGameStart(state(false, true, false)));
    }

    @Test
    public void endsOnTerminalObservation() {
        assertTrue(Minecraft18RunBoundary.isGameEnd(state(false, true, false)));
        assertTrue(Minecraft18RunBoundary.isGameEnd(state(true, false, false)));
        assertTrue(Minecraft18RunBoundary.isGameEnd(state(true, true, true)));
        assertFalse(Minecraft18RunBoundary.isGameEnd(state(true, true, false)));
    }

    @Test
    public void usesTheSameTerminalChatPhrasesAsTheAi() {
        assertTrue(Minecraft18RunBoundary.isTerminalChat("You fell off the maze!"));
        assertTrue(Minecraft18RunBoundary.isTerminalChat("Solo run over"));
        assertTrue(Minecraft18RunBoundary.isTerminalChat("You weren't on the safe pad"));
        assertFalse(Minecraft18RunBoundary.isTerminalChat("Welcome to Monster Maze"));
    }
}
