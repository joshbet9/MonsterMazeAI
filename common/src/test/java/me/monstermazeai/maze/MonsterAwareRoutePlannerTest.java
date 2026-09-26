package me.monstermazeai.maze;

import me.monstermazeai.game.GameState;
import me.monstermazeai.monster.MonsterState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MonsterAwareRoutePlannerTest {
    private static MazeModel openMaze() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int r = 0; r < MazeModel.SIZE; r++)
            for (int c = 0; c < MazeModel.SIZE; c++) raw[r][c] = 1;
        return new MazeModel(raw);
    }

    @Test
    void emptyMonsterFieldMatchesShortestRoute() {
        GameState state = new GameState();
        state.maze = openMaze();

        PlayerRoute route = new MonsterAwareRoutePlanner().route(
                state, new Cell(0, 0), new Cell(0, 3));

        assertEquals(4, route.size());
        assertEquals(new Cell(0, 0), route.cells().get(0));
        assertEquals(new Cell(0, 3), route.cells().get(3));
    }

    @Test
    void activeSafePadCellsRemainReachableToPlayerRouting() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        raw[0][0] = 1;
        raw[0][1] = 1;

        MazeModel maze = new MazeModel(raw);
        maze.setDisabled(0, 2, true); // Active Safe Pad surface: monster-disabled, player-walkable.

        GameState state = new GameState();
        state.maze = maze;

        PlayerRoute route = new MonsterAwareRoutePlanner().route(
                state, new Cell(0, 0), new Cell(0, 2));

        assertEquals(new Cell(0, 2), route.cells().get(route.size() - 1));
    }

    @Test
    void nearbyHarmfulMonsterCanInfluenceLocalRoute() {
        GameState state = new GameState();
        state.maze = openMaze();

        MonsterState monster = new MonsterState(7, 1.5, 0.0, 1.5);
        monster.vx = 0.0;
        monster.vz = 0.0;
        state.monsters.add(monster);

        PlayerRoute route = new MonsterAwareRoutePlanner().route(
                state, new Cell(0, 1), new Cell(2, 1));

        assertFalse(route.cells().contains(new Cell(1, 1)),
                "A nearby stationary monster that would knock the player away from the pad should make the planner choose a local detour.");
        assertEquals(new Cell(0, 1), route.cells().get(0));
        assertEquals(new Cell(2, 1), route.cells().get(route.size() - 1));
    }

    @Test
    void distantMonsterDoesNotDistortShortestRoute() {
        GameState state = new GameState();
        state.maze = openMaze();

        MonsterState distant = new MonsterState(1, 20.5, 0.0, 20.5);
        state.monsters.add(distant);

        PlayerRoute route = new MonsterAwareRoutePlanner().route(
                state, new Cell(0, 1), new Cell(2, 1));

        assertEquals(List.of(new Cell(0, 1), new Cell(1, 1), new Cell(2, 1)), route.cells(),
                "A distant monster must not distort the optimal baseline route.");
    }

    @Test
    void usefulKnockbackIsNotPenalizedAsAThreat() {
        GameState state = new GameState();
        state.maze = openMaze();
        state.player.x = 2.5;
        state.player.z = 1.5;

        MonsterState monster = new MonsterState(8, 1.5, 0.0, 1.5);
        state.monsters.add(monster);

        PlayerRoute route = new MonsterAwareRoutePlanner().route(
                state, new Cell(2, 1), new Cell(2, 3));

        assertEquals(new Cell(2, 1), route.cells().get(0));
        assertEquals(new Cell(2, 3), route.cells().get(route.size() - 1));
    }
}

