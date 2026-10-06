package me.monstermazeai.game;

public final class TimerModel {
    public int initialTicks(Mode mode, int stage) {
        if (mode == Mode.ORIGINAL || mode == Mode.SPEED)
            return Math.max(15, 60 - ((stage - 1) * 2)) * 20;
        return Math.max(15, 35 - ((stage - 1) * 20 / 9)) * 20;
    }

    public int shortenedTicks(int currentTicks, int stage, boolean allAliveOnPad) {
        if (allAliveOnPad) return Math.min(currentTicks, 4 * 20);
        int seconds = Math.max(6, 16 - (stage - 1));
        return Math.min(currentTicks, seconds * 20);
    }
}