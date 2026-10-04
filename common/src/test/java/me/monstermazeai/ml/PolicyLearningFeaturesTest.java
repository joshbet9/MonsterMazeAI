package me.monstermazeai.ml;

import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.player.Action;
import me.monstermazeai.testdata.SourceMazeLayouts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PolicyLearningFeaturesTest {
    @AfterEach
    void clearPolicyProperties() {
        System.clearProperty("monstermaze.ml.policy.model");
        System.clearProperty("monstermaze.ml.mode");
        System.clearProperty("monstermaze.ml.policy.explore");
        System.clearProperty("monstermaze.ml.policy.record");
        System.clearProperty("monstermaze.ml.policy.output");
    }

    @Test
    void featureVectorIsFixedAndFinite() {
        GameState state = new GameState();
        state.mode = me.monstermazeai.game.Mode.SPEED;
        state.kit = Kit.REPULSOR;
        state.maze = new MazeModel(SourceMazeLayouts.maze(0));
        state.player.x = 49.5;
        state.player.y = GameState.PATH_Y;
        state.player.z = 49.5;
        state.player.grounded = true;
        state.activePadRow = 24;
        state.activePadColumn = 24;
        Action action = new Action(1, -1, false, true, 30, false);

        double[] features = PolicyLearningFeatures.extract(state, action);

        assertEquals(PolicyLearningFeatures.NAMES.length, features.length);
        assertEquals(44, features.length);
        for (double value : features) {
            assertTrue(Double.isFinite(value), "non-finite feature");
        }
    }

    @Test
    void selectorFallsBackToBaselineWithoutPolicy() {
        GameState state = new GameState();
        state.mode = me.monstermazeai.game.Mode.MODERN;
        state.kit = Kit.MAVERICK;
        state.alive = true;
        state.inMonsterMaze = true;
        state.maze = new MazeModel(SourceMazeLayouts.maze(0));
        state.activePadRow = 50;
        state.activePadColumn = 50;

        Action baseline = new Action(1, 0, false, true, 0, false);
        Action selected = PolicyActionSelector.select(state, baseline, true);

        assertEquals(baseline, selected);
    }
}
