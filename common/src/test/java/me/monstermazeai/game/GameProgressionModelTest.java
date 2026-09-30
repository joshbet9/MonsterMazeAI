package me.monstermazeai.game;

import me.monstermazeai.ability.AbilityModel;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.MazeModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GameProgressionModelTest {
    private static GameState state(int stage, double health) {
        GameState state = new GameState();
        state.mode = Mode.MODERN;
        state.kit = Kit.REPULSOR;
        state.maze = new MazeModel(openMaze());
        state.player.x = 10.5;
        state.player.y = GameState.PATH_Y;
        state.player.z = 10.5;
        state.player.grounded = true;
        state.player.health = health;
        state.player.maxHealth = 20.0;
        state.activePadRow = 10;
        state.activePadColumn = 10;
        state.stage = stage;
        state.padReached = false;
        new AbilityModel().initialiseForMode(state);
        state.phaseTicksRemaining = new TimerModel().initialTicks(state.mode, stage);
        return state;
    }

    @Test
    void onlyStageOneGetsFirstPadHealing() {
        GameProgressionModel progression = new GameProgressionModel();

        GameState stageOne = state(1, 10.0);
        progression.tick(stageOne);
        assertEquals(14.0, stageOne.player.health, 1e-9);

        GameState stageTwo = state(2, 10.0);
        progression.tick(stageTwo);
        assertEquals(12.0, stageTwo.player.health, 1e-9);
    }

    @Test
    void padCaptureShortensSoloModernPhaseToFourSeconds() {
        GameProgressionModel progression = new GameProgressionModel();
        GameState state = state(5, 10.0);
        state.phaseTicksRemaining = 600;

        progression.tick(state);

        assertTrue(state.padReached);
        assertEquals(80, state.phaseTicksRemaining);
    }

    private static int[][] openMaze() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int row = 0; row < MazeModel.SIZE; row++) {
            for (int column = 0; column < MazeModel.SIZE; column++) {
                raw[row][column] = 1;
            }
        }
        return raw;
    }
}
