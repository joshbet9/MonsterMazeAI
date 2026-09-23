package me.monstermazeai.game;

import me.monstermazeai.ability.AbilityModel;

/**
 * Source-grounded round/phase progression for the single-player AI simulator.
 *
 * The real server has a per-tick task for pad checks and a separate task every
 * 20 ticks for the phase timer and center deterioration.
 */
public final class GameProgressionModel {
    private final TimerModel timer = new TimerModel();
    private final AbilityModel abilities;

    public GameProgressionModel() {
        this(new AbilityModel());
    }

    public GameProgressionModel(AbilityModel abilities) {
        this.abilities = abilities;
    }

    public void initialise(GameState state) {
        state.stage = 1;
        state.phaseTicksRemaining = timer.initialTicks(state.mode, state.stage);
        state.phaseSecondAccumulatorTicks = 0;
        state.liveSeconds = 0;
        state.centerSafeZoneDecay = 11;
        state.previewPadRequested = false;
        state.pendingMonsterSpawns = initialMonsterCount(state.mode);
        state.padReached = false;
    }

    public void tick(GameState state) {
        if (!state.alive) return;

        boolean onActive = isOnPad(state, state.activePadRow, state.activePadColumn);

        // checkPlayersOnSafePad() runs every server tick.
        if (onActive && !state.padReached) {
            state.padReached = true;
            abilities.onReachedPad(state, true);

            int shortenedSeconds = Math.max(6, 16 - (state.stage - 1));
            state.phaseTicksRemaining = Math.min(
                    state.phaseTicksRemaining, shortenedSeconds * 20);
        }

        // In solo mode, all alive players are on the pad when this one player is
        // on it, so the source's four-second shortening applies.
        if (onActive) {
            state.phaseTicksRemaining = Math.min(state.phaseTicksRemaining, 4 * 20);
        }

        state.phaseSecondAccumulatorTicks++;
        if (state.phaseSecondAccumulatorTicks < 20) return;
        state.phaseSecondAccumulatorTicks = 0;
        state.liveSeconds++;

        // decrementPhaseTime() runs once per second.
        if (state.phaseTicksRemaining > 0) {
            state.phaseTicksRemaining -= 20;
        }

        // The preview pad is created when the displayed timer reaches 2 seconds.
        // The actual random location is supplied by the live-game adapter.
        if (state.phaseTicksRemaining == 2 * 20 && state.previewPadRow < 0) {
            state.previewPadRequested = true;
        }

        // Center deterioration starts 20 seconds after LIVE and then advances
        // once per second through the source's 11-step decay sequence.
        if (state.liveSeconds >= 20 && state.centerSafeZoneDecay > 0) {
            state.centerSafeZoneDecay--;
        }

        if (state.phaseTicksRemaining > 0) return;

        if (!onActive) {
            state.alive = false;
            return;
        }

        advancePhase(state);
    }

    private void advancePhase(GameState state) {
        state.stage++;
        state.phaseTicksRemaining = timer.initialTicks(state.mode, state.stage);
        state.phaseSecondAccumulatorTicks = 0;
        state.padReached = false;

        // Source promotes nextSafePad to safePad at the phase boundary.
        state.activePadRow = state.previewPadRow;
        state.activePadColumn = state.previewPadColumn;
        state.previewPadRow = -1;
        state.previewPadColumn = -1;
        state.previewPadRequested = false;

        // Source spawns 15 additional monsters in Original/Speed and 30 in
        // Modern/Classic before promoting the next pad.
        state.pendingMonsterSpawns += additionalMonsterCount(state.mode);
    }

    private int initialMonsterCount(Mode mode) {
        return mode == Mode.MODERN || mode == Mode.CLASSIC ? 225 : 150;
    }

    private int additionalMonsterCount(Mode mode) {
        return mode == Mode.MODERN || mode == Mode.CLASSIC ? 30 : 15;
    }

    private boolean isOnPad(GameState state, int row, int column) {
        if (row < 0 || column < 0) return false;
        return PadModel.isOn(
                state.player,
                row + 0.5,
                GameState.PAD_SURFACE_Y,
                column + 0.5);
    }
}
