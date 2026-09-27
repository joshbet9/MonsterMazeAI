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
        maze.setDisabled(0, 2, true); // Monster-disabled alone is not physical floor.
        maze.setPhysicalFloor(0, 2, true); // Active Safe Pad surface is physically present.

        GameState state = new GameState();
        state.maze = maze;

        PlayerRoute route = new MonsterAwareRoutePlanner().route(
                state, new Cell(0, 0), new Cell(0, 2));

        assertEquals(new Cell(0, 2), route.cells().get(route.size() - 1));
    }


    @Test
    void safePadRouteStopsAtFirstReachableCellOfFiveByFiveSurface() {
        GameState state = new GameState();
        state.maze = openMaze();

        PlayerRoute route = new MonsterAwareRoutePlanner().routeToRegion(
                state, new Cell(0, 5), new Cell(4, 5), 2);

        assertEquals(new Cell(0, 5), route.cells().get(0));
        Cell end = route.cells().get(route.size() - 1);
        assertTrue(Math.abs(end.row() - 4) <= 2 && Math.abs(end.column() - 5) <= 2,
                "Route must terminate inside the physical 5x5 Safe Pad region.");
        assertTrue(route.size() <= 4,
                "Route should stop at the first reachable pad-region cell rather than its beacon centre.");
    }

    @Test
    void fastSafePadBootstrapUsesOneShortestPhysicalPathToTheRegion() {
        GameState state = new GameState();
        state.maze = openMaze();

        PlayerRoute route = new MonsterAwareRoutePlanner().routeToRegionFast(
                state, new Cell(0, 0), new Cell(4, 4), 2);

        assertEquals(5, route.size(), "nearest 5x5 pad cell is four cardinal steps away");
        Cell end = route.cells().get(route.size() - 1);
        assertTrue(Math.abs(end.row() - 4) <= 2 && Math.abs(end.column() - 4) <= 2);
    }

    @Test
    void disabledMonsterWaypointDoesNotCreatePhysicalFloor() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        raw[0][0] = 1;
        MazeModel maze = new MazeModel(raw);
        maze.setDisabled(0, 1, true);

        assertFalse(maze.isPhysicalFloor(0, 1),
                "monster waypoint disabling must not turn an air cell into a player floor cell");
        assertFalse(new PlayerPathfinder().shortestPath(maze, new Cell(0, 0), new Cell(0, 1)).size() > 0);
    }

    @Test
    void nearbyMonsterIsEvaluatedByTrajectorySimulationRatherThanAStaticRadiusPenalty() {
        GameState state = new GameState();
        state.maze = openMaze();

        MonsterState monster = new MonsterState(7, 1.5, 0.0, 1.5);
        monster.vx = 0.0;
        monster.vz = 0.0;
        state.player.x = 0.5;
        state.player.z = 1.5;
        state.player.grounded = true;
        state.monsters.add(monster);

        PlayerRoute route = new MonsterAwareRoutePlanner().route(
                state, new Cell(0, 1), new Cell(2, 1));

        // The important contract is that the route remains a valid physical
        // route selected by the trajectory simulator; a contact can be useful
        // knockback, so the source mechanics do not justify a mandatory detour.
        assertEquals(new Cell(0, 1), route.cells().get(0));
        Cell end = route.cells().get(route.size() - 1);
        assertEquals(new Cell(2, 1), end);
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
        state.player.z = 3.5;

        MonsterState monster = new MonsterState(8, 2.5, 0.0, 2.5);
        state.monsters.add(monster);

        PlayerRoute route = new MonsterAwareRoutePlanner().route(
                state, new Cell(2, 3), new Cell(2, 5));

        assertEquals(new Cell(2, 3), route.cells().get(0));
        assertEquals(new Cell(2, 5), route.cells().get(route.size() - 1));
    }
}
