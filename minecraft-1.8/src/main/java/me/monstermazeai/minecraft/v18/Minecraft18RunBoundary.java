package me.monstermazeai.minecraft.v18;

import me.monstermazeai.adapter.LegacyWorldObservation;

/**
 * Shared Monster Maze run-boundary rules for the 1.8.9 adapter.
 *
 * Game start is determined by Minecraft18Observer's authoritative
 * inMonsterMaze observation. Terminal state is determined from the same
 * observation, plus the exact terminal chat phrases already used by the AI
 * runtime. This keeps the human recorder and AI run accounting on one
 * boundary definition.
 */
public final class Minecraft18RunBoundary {
    private Minecraft18RunBoundary() {
    }

    public static boolean isGameStart(LegacyWorldObservation state) {
        return state != null && state.inMonsterMaze;
    }

    public static boolean isGameEnd(LegacyWorldObservation state) {
        return state != null && (!state.inMonsterMaze || !state.alive || state.completed);
    }

    public static boolean isTerminalChat(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("fell off the maze")
                || lower.contains("solo run over")
                || lower.contains("you weren't on the safe pad");
    }
}
