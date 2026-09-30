package me.monstermazeai.game;

import me.monstermazeai.ability.AbilityModel;
import me.monstermazeai.maze.Cell;

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
        state.oldPads.clear();
        state.oldPadDecaySeconds.clear();
    }

    public void tick(GameState state) {
        if (!state.alive) return;

        syncPadSurfaces(state);
        boolean onActive = isOnPad(state, state.activePadRow, state.activePadColumn);

        // checkPlayersOnSafePad() runs every server tick.
        if (onActive && !state.padReached) {
            state.padReached = true;
            abilities.onReachedPad(state, state.stage == 1);

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

        // SafePad decay runs once per second. The source keeps each old pad's
        // physical 5x5 surface for 11 decay ticks before restoring the maze.
        decayOldPads(state);

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
        if (state.activePadRow >= 0 && state.activePadColumn >= 0) {
            Cell oldPad = new Cell(state.activePadRow, state.activePadColumn);
            if (!state.oldPads.contains(oldPad)) state.oldPads.add(oldPad);
            state.oldPadDecaySeconds.put(oldPad, 11);
            installPadSurface(state, oldPad);
        }

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
        if (state.activePadRow >= 0 && state.activePadColumn >= 0) {
            installPadSurface(state, new Cell(state.activePadRow, state.activePadColumn));
        }

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

    /** Apply the source SafePad surface to any externally supplied active/preview pad coordinates. */
    public void syncPadSurfaces(GameState state) {
        if (state == null || state.maze == null) return;
        installPadSurface(state, new Cell(state.activePadRow, state.activePadColumn));
        installPadSurface(state, new Cell(state.previewPadRow, state.previewPadColumn));
        for (Cell oldPad : state.oldPads) installPadSurface(state, oldPad);
    }

    private void installPadSurface(GameState state, Cell pad) {
        if (pad == null || pad.row() < 0 || pad.column() < 0
                || pad.row() >= me.monstermazeai.maze.MazeModel.SIZE
                || pad.column() >= me.monstermazeai.maze.MazeModel.SIZE) return;
        for (int row = pad.row() - 2; row <= pad.row() + 2; row++) {
            for (int column = pad.column() - 2; column <= pad.column() + 2; column++) {
                state.maze.setPadSurface(row, column, true);
            }
        }
    }

    private void decayOldPads(GameState state) {
        if (state.oldPadDecaySeconds.isEmpty()) return;
        var it = state.oldPadDecaySeconds.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            Cell pad = entry.getKey();
            int remaining = entry.getValue() - 1;
            if (remaining > 0) {
                entry.setValue(remaining);
                continue;
            }
            for (int row = pad.row() - 2; row <= pad.row() + 2; row++) {
                for (int column = pad.column() - 2; column <= pad.column() + 2; column++) {
                    state.maze.setPadSurface(row, column, false);
                }
            }
            state.oldPads.remove(pad);
            it.remove();
        }
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
