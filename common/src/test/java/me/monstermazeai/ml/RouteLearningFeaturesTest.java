package me.monstermazeai.ml;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.planner.TacticalRouteSimulator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RouteLearningFeaturesTest {
    @Test
    void featureVectorHasStableShapeAndFiniteValues() {
        GameState state = new GameState();
        state.maze = new MazeModel(filledMaze());
        state.player.x = 10.5;
        state.player.y = GameState.PATH_Y;
        state.player.z = 10.5;
        state.player.grounded = true;

        PlayerRoute route = new PlayerRoute(List.of(
                new Cell(10, 10),
                new Cell(10, 11),
                new Cell(11, 11)));

        double[] features = RouteLearningFeatures.extract(state, route, new Cell(20, 20));

        assertEquals(RouteLearningFeatures.NAMES.length, features.length);
        for (double feature : features) {
            assertTrue(Double.isFinite(feature));
        }
    }

    @Test
    void routeCountsTreatTwoCellEdgesAsGaps() {
        PlayerRoute route = new PlayerRoute(List.of(
                new Cell(10, 10),
                new Cell(12, 10),
                new Cell(12, 11)));

        assertEquals(1, RouteLearningFeatures.gapCount(route));
        assertEquals(1, RouteLearningFeatures.turnCount(route));
    }

    private static int[][] filledMaze() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int r = 0; r < MazeModel.SIZE; r++) {
            java.util.Arrays.fill(raw[r], 1);
        }
        return raw;
    }

    @Test
    void reachedOutcomeProducesLowerCostThanADeath() {
        GameState state = new GameState();
        state.maze = new MazeModel(filledMaze());

        PlayerRoute route = new PlayerRoute(List.of(
                new Cell(10, 10),
                new Cell(10, 11)));

        TacticalRouteSimulator.Result success = new TacticalRouteSimulator.Result(
                true, 20, 20.0, 0.0, state, 1);
        TacticalRouteSimulator.Result failure = new TacticalRouteSimulator.Result(
                false, Integer.MAX_VALUE, 12.0, 8.0, state, 0);

        assertTrue(RouteLearningFeatures.target(state, route, success)
                < RouteLearningFeatures.target(state, route, failure));
    }
}
