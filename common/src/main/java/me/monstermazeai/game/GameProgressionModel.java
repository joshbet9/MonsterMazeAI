package me.monstermazeai.game;

import me.monstermazeai.ability.AbilityModel;

/**
 * Source-grounded round/phase progression for the single-player AI simulator.
 *
 * The simulator represents the current active pad and the already-generated
 * preview pad. Pad selection itself remains an observation concern because
 * the live game chooses it from the maze's random pad generator.
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
        state.padReached = false;
    }

    public void tick(GameState state) {
        if (!state.alive) return;

        boolean onActive = isOnPad(state, state.activePadRow, state.activePadColumn);
        boolean onPreview = isOnPad(state, state.previewPadRow, state.previewPadColumn);

        // The first arrival shortens the current phase. The reward is granted
        // once per player per phase, represented by padReached.
        if (onActive && !state.padReached) {
            state.padReached = true;
            abilities.onReachedPad(state, true);

            int shortenedSeconds = Math.max(6, 16 - (state.stage - 1));
            state.phaseTicksRemaining = timer.shortenedTicks(
                    state.phaseTicksRemaining, state.stage, false);
        }

        // A solo run has one alive player, so being on the pad is equivalent
        // to the "all alive players on pad" condition.
        if (onActive) {
            state.phaseTicksRemaining = Math.min(
                    state.phaseTicksRemaining, 4 * 20);
        }

        if (state.phaseTicksRemaining > 0) {
            state.phaseTicksRemaining--;
        }

        if (state.phaseTicksRemaining > 0) return;

        // Survival requires being on the active pad at the phase boundary.
        if (!onActive) {
            state.alive = false;
            return;
        }

        advancePhase(state);
    }

    private void advancePhase(GameState state) {
        state.stage++;
        state.phaseTicksRemaining = timer.initialTicks(state.mode, state.stage);
        state.padReached = false;

        // The preview becomes the new active pad. The live client must provide
        // its generated location before this transition.
        state.activePadRow = state.previewPadRow;
        state.activePadColumn = state.previewPadColumn;
        state.previewPadRow = -1;
        state.previewPadColumn = -1;

        // Monster-wave growth is represented separately by the live observation
        // layer; this model does not invent spawn positions or counts.
    }

    private boolean isOnPad(GameState state, int row, int column) {
        if (row < 0 || column < 0) return false;

        double dx = state.player.x - (row + 0.5);
        double dz = state.player.z - (column + 0.5);

        return dx > -2.5 && dx < 2.5
                && dz > -2.5 && dz < 2.5
                && state.player.y > 0.0
                && state.player.y < 5.0;
    }
}
