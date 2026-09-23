package me.monstermazeai.game;

import me.monstermazeai.ability.AbilityModel;

/**
 * Source-grounded round/phase progression for the single-player AI simulator.
 *
 * The real server's phase timer is an integer number of seconds and is
 * decremented by a Bukkit task scheduled every 20 ticks. The common simulator
 * therefore keeps phaseTicksRemaining in ticks but only decrements it once per
 * 20 simulated ticks.
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
        state.padReached = false;
    }

    public void tick(GameState state) {
        if (!state.alive) return;

        boolean onActive = isOnPad(state, state.activePadRow, state.activePadColumn);

        // GameManager.checkPlayersOnSafePad() runs every server tick. The first
        // player to reach the active pad shortens the phase and receives the kit
        // reward exactly once for this phase.
        if (onActive && !state.padReached) {
            state.padReached = true;
            abilities.onReachedPad(state, true);

            int shortened = timer.shortenedTicks(
                    state.phaseTicksRemaining, state.stage, false);
            state.phaseTicksRemaining = Math.min(
                    state.phaseTicksRemaining, shortened);
        }

        // In solo mode, the single alive player is also the "all alive players"
        // condition used by the live game to force the timer down to 4 seconds.
        if (onActive) {
            state.phaseTicksRemaining = Math.min(
                    state.phaseTicksRemaining, 4 * 20);
        }

        // Source GameManager schedules decrementPhaseTime() with
        // runTaskTimer(..., 20L, 20L), so this is deliberately NOT a per-tick
        // countdown.
        state.phaseSecondAccumulatorTicks++;
        if (state.phaseSecondAccumulatorTicks < 20) return;
        state.phaseSecondAccumulatorTicks = 0;

        if (state.phaseTicksRemaining <= 0) return;

        // decrementPhaseTime() does phaseTimer-- once per second.
        state.phaseTicksRemaining -= 20;

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
        state.phaseSecondAccumulatorTicks = 0;
        state.padReached = false;

        // The live game generates nextSafePad when the phase reaches 2 seconds,
        // then promotes it to safePad at zero. The client adapter must observe
        // and supply previewPadRow/Column before this transition.
        state.activePadRow = state.previewPadRow;
        state.activePadColumn = state.previewPadColumn;
        state.previewPadRow = -1;
        state.previewPadColumn = -1;

        // Monster spawning/removal is deliberately not invented here; the live
        // observation layer supplies the authoritative wave state.
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
