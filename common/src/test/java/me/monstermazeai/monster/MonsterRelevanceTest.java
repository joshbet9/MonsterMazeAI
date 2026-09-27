package me.monstermazeai.monster;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.maze.PlayerRoute;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MonsterRelevanceTest {
    private static MazeModel openMaze() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int r = 0; r < MazeModel.SIZE; r++) {
            for (int c = 0; c < MazeModel.SIZE; c++) raw[r][c] = 1;
        }
        return new MazeModel(raw);
    }

    @Test
    void usesTwentyBlockLocalRadiusForCurrentPlayer() {
        GameState state = new GameState();
        state.maze = openMaze();
        state.player.x = 50.5;
        state.player.y = 0.0;
        state.player.z = 50.5;

        MonsterState inside = new MonsterState(1, 70.0, 0.0, 50.5);
        MonsterState outside = new MonsterState(2, 70.6, 0.0, 50.5);

        assertTrue(MonsterRelevance.withinPlayerRadius(
                inside, state.player, MonsterRelevance.INTERACTION_RADIUS));
        assertFalse(MonsterRelevance.withinPlayerRadius(
                outside, state.player, MonsterRelevance.INTERACTION_RADIUS));
    }

    @Test
    void excludesFutureRouteThreatsOutsideCurrentInteractionRadius() {
        GameState state = new GameState();
        state.maze = openMaze();
        state.player.x = 50.5;
        state.player.z = 50.5;

        PlayerRoute route = new PlayerRoute(List.of(
                new Cell(50, 50),
                new Cell(50, 51),
                new Cell(50, 52),
                new Cell(50, 53),
                new Cell(50, 54)
        ));

        // More than 20 blocks from the player: do not pay the expensive
        // simulator cost yet. A later observation will pick it up when it
        // enters the local interaction sphere.
        MonsterState futureThreat = new MonsterState(1, 50.5, 0.0, 72.0);
        MonsterState irrelevant = new MonsterState(2, 80.0, 0.0, 80.0);
        state.monsters.add(futureThreat);
        state.monsters.add(irrelevant);

        GameState filtered = MonsterRelevance.copyForRoute(state, route);

        assertTrue(filtered.monsters.isEmpty());
        assertEquals(2, state.monsters.size());
    }

    @Test
    void retainsMonsterInsideCurrentInteractionRadiusRegardlessOfFutureRoutePosition() {
        GameState state = new GameState();
        state.maze = openMaze();
        state.player.x = 50.5;
        state.player.z = 50.5;

        PlayerRoute route = new PlayerRoute(List.of(
                new Cell(50, 50),
                new Cell(50, 51)
        ));

        MonsterState nearby = new MonsterState(3, 50.5, 0.0, 69.5);
        state.monsters.add(nearby);

        GameState filtered = MonsterRelevance.copyForRoute(state, route);

        assertEquals(1, filtered.monsters.size());
        assertEquals(3, filtered.monsters.get(0).id);
    }

    @Test
    void doesNotMutateLiveObservationWhenFiltering() {
        GameState state = new GameState();
        state.maze = openMaze();
        state.player.x = 50.5;
        state.player.z = 50.5;
        state.monsters.add(new MonsterState(1, 50.5, 0.0, 50.5));
        state.monsters.add(new MonsterState(2, 90.5, 0.0, 90.5));

        PlayerRoute route = new PlayerRoute(List.of(
                new Cell(50, 50),
                new Cell(50, 51)
        ));

        GameState filtered = MonsterRelevance.copyForRoute(state, route);

        assertEquals(2, state.monsters.size());
        assertEquals(1, filtered.monsters.size());
    }
}
