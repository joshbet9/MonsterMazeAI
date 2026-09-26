package me.monstermazeai.maze;

import me.monstermazeai.game.GameState;
import me.monstermazeai.monster.MonsterState;
import org.junit.jupiter.api.Test;

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
    void routeDetoursAroundPredictedMonster() {
        GameState state = new GameState();
        state.maze = openMaze();

        MonsterState monster = new MonsterState(7, 1.5, 0.0, 1.5);
        monster.vx = 0.0;
        monster.vz = 0.0;
        state.monsters.add(monster);

        PlayerRoute route = new MonsterAwareRoutePlanner().route(
                state, new Cell(0, 1), new Cell(2, 1));

        assertFalse(route.cells().contains(new Cell(1, 1)),
                "A stationary monster occupying the direct corridor should make the planner choose a safe detour.");
        assertEquals(new Cell(0, 1), route.cells().get(0));
        assertEquals(new Cell(2, 1), route.cells().get(route.size() - 1));
    }

    @Test
    void removedFrozenAndLaunchedMonstersDoNotAddRouteRisk() {
        GameState state = new GameState();
        state.maze = openMaze();

        MonsterState removed = new MonsterState(1, 1.5, 0.0, 1.5);
        removed.removed = true;
        MonsterState frozen = new MonsterState(2, 1.5, 0.0, 1.5);
        frozen.frozenUntilTick = 20;
        MonsterState launched = new MonsterState(3, 1.5, 0.0, 1.5);
        launched.launchedUntilTick = 20;

        state.monsters.add(removed);
        state.monsters.add(frozen);
        state.monsters.add(launched);

        PlayerRoute route = new MonsterAwareRoutePlanner().route(
                state, new Cell(0, 1), new Cell(2, 1));

        assertTrue(route.cells().contains(new Cell(1, 1)));
    }
}
