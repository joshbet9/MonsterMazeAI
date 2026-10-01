package me.monstermazeai.maze;

import me.monstermazeai.game.GameState;
import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
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
        PlayerRoute route = new MonsterAwareRoutePlanner().route(state, new Cell(0, 0), new Cell(0, 3));
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
        maze.setDisabled(0, 2, true);
        maze.setPhysicalFloor(0, 2, true);
        GameState state = new GameState();
        state.maze = maze;
        PlayerRoute route = new MonsterAwareRoutePlanner().route(state, new Cell(0, 0), new Cell(0, 2));
        assertEquals(new Cell(0, 2), route.cells().get(route.size() - 1));
    }

    @Test
    void safePadRouteStopsAtFirstReachableCellOfFiveByFiveSurface() {
        GameState state = new GameState();
        state.maze = openMaze();
        PlayerRoute route = new MonsterAwareRoutePlanner().routeToRegion(state, new Cell(0, 5), new Cell(4, 5), 2);
        assertEquals(new Cell(0, 5), route.cells().get(0));
        Cell end = route.cells().get(route.size() - 1);
        assertTrue(Math.abs(end.row() - 4) <= 2 && Math.abs(end.column() - 5) <= 2);
        assertTrue(route.size() <= 4);
    }

    @Test
    void fastSafePadBootstrapUsesOneShortestPhysicalPathToTheRegion() {
        GameState state = new GameState();
        state.maze = openMaze();
        PlayerRoute route = new MonsterAwareRoutePlanner().routeToRegionFast(state, new Cell(0, 0), new Cell(4, 4), 2);
        assertEquals(5, route.size());
        Cell end = route.cells().get(route.size() - 1);
        assertTrue(Math.abs(end.row() - 4) <= 2 && Math.abs(end.column() - 4) <= 2);
    }

    @Test
    void disabledMonsterWaypointDoesNotCreatePhysicalFloor() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        raw[0][0] = 1;
        MazeModel maze = new MazeModel(raw);
        maze.setDisabled(0, 1, true);
        assertFalse(maze.isPhysicalFloor(0, 1));
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
        PlayerRoute route = new MonsterAwareRoutePlanner().route(state, new Cell(0, 1), new Cell(2, 1));
        assertEquals(new Cell(0, 1), route.cells().get(0));
        assertEquals(new Cell(2, 1), route.cells().get(route.size() - 1));
    }

    @Test
    void nearbyMonsterDoesNotCauseSafePadGlobalDetour() {
        GameState state = new GameState();
        state.maze = openMaze();
        state.player.x = 2.5;
        state.player.z = 2.5;
        MonsterState monster = new MonsterState(9, 2.5, 0.0, 3.5);
        state.monsters.add(monster);

        PlayerRoute route = new MonsterAwareRoutePlanner()
                .routeToRegionFast(state, new Cell(2, 2), new Cell(2, 6), 0);

        assertEquals(List.of(
                new Cell(2, 2), new Cell(2, 3), new Cell(2, 4),
                new Cell(2, 5), new Cell(2, 6)), route.cells());
    }

    @Test
    void distantMonsterDoesNotDistortShortestRoute() {
        GameState state = new GameState();
        state.maze = openMaze();
        MonsterState distant = new MonsterState(1, 20.5, 0.0, 20.5);
        state.monsters.add(distant);
        PlayerRoute route = new MonsterAwareRoutePlanner().route(state, new Cell(0, 1), new Cell(2, 1));
        assertEquals(List.of(new Cell(0, 1), new Cell(1, 1), new Cell(2, 1)), route.cells());
    }

    @Test
    void usefulKnockbackIsNotPenalizedAsAThreat() {
        GameState state = new GameState();
        state.maze = openMaze();
        state.player.x = 2.5;
        state.player.z = 3.5;
        MonsterState monster = new MonsterState(8, 2.5, 0.0, 2.5);
        state.monsters.add(monster);
        PlayerRoute route = new MonsterAwareRoutePlanner().route(state, new Cell(2, 3), new Cell(2, 5));
        assertEquals(new Cell(2, 3), route.cells().get(0));
        assertEquals(new Cell(2, 5), route.cells().get(route.size() - 1));
    }

    @Test
    void gapRiskCanPreferAnOrdinaryRouteOverAValuableShortcut() {
        GameState state = new GameState();
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int r = 0; r < MazeModel.SIZE; r++)
            for (int c = 0; c < MazeModel.SIZE; c++) raw[r][c] = 1;
        raw[10][11] = 0;
        state.maze = new MazeModel(raw);

        PlayerRoute route = new MonsterAwareRoutePlanner(new GapJumpPolicy(3.0))
                .routeFast(state, new Cell(10, 10), new Cell(10, 14));

        assertEquals(7, route.size());
        for (int i = 0; i + 1 < route.size(); i++) {
            int dr = Math.abs(route.cells().get(i + 1).row() - route.cells().get(i).row());
            int dc = Math.abs(route.cells().get(i + 1).column() - route.cells().get(i).column());
            assertFalse((dr == 2 && dc == 0) || (dc == 2 && dr == 0));
        }
    }

    @Test
    void zeroGapRiskAllowsThePhysicalShortcutWhenItIsActuallyShorter() {
        GameState state = new GameState();
        // This test exercises only the configurable gap-cost policy.
        // Non-Jumper kits can still use the source-faithful horizontal
        // sprint-jump ("speeding") technique across a one-block void.
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int r = 0; r < MazeModel.SIZE; r++)
            for (int c = 0; c < MazeModel.SIZE; c++) raw[r][c] = 1;
        raw[10][11] = 0;
        raw[10][13] = 0;
        state.maze = new MazeModel(raw);
        state.mode = Mode.SPEED;
        state.kit = Kit.MAVERICK;

        PlayerRoute route = new MonsterAwareRoutePlanner(new GapJumpPolicy(0.0))
                .routeFast(state, new Cell(10, 10), new Cell(10, 14));

        assertEquals(List.of(new Cell(10, 10), new Cell(10, 12), new Cell(10, 14)), route.cells());
    }

    @Test
    void baselineRoutePolicyUsesARealGapShortcutWhenItSavesRouteEdges() {
        GameState state = new GameState();
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int r = 0; r < MazeModel.SIZE; r++)
            for (int c = 0; c < MazeModel.SIZE; c++) raw[r][c] = 1;
        raw[10][11] = 0;
        raw[10][13] = 0;
        state.maze = new MazeModel(raw);
        state.mode = Mode.SPEED;
        state.kit = Kit.MAVERICK;

        PlayerRoute route = new MonsterAwareRoutePlanner()
                .routeFast(state, new Cell(10, 10), new Cell(10, 14));

        assertEquals(List.of(
                new Cell(10, 10),
                new Cell(10, 12),
                new Cell(10, 14)), route.cells());
    }

    @Test
    void modernNonJumperSharesSpeedGapMechanic() {
        GameState state = new GameState();
        state.mode = Mode.MODERN;
        state.kit = Kit.MAVERICK;

        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int r = 0; r < MazeModel.SIZE; r++)
            for (int c = 0; c < MazeModel.SIZE; c++) raw[r][c] = 1;
        raw[10][11] = 0;
        raw[10][13] = 0;
        state.maze = new MazeModel(raw);

        PlayerRoute route = new MonsterAwareRoutePlanner(new GapJumpPolicy(0.0))
                .route(state, new Cell(10, 10), new Cell(10, 14));

        assertEquals(List.of(
                new Cell(10, 10),
                new Cell(10, 12),
                new Cell(10, 14)), route.cells());
    }

    @Test
    void fullRoutingRetainsAnOrdinaryRouteEvenWhenGapCandidatesExist() {
        GameState state = new GameState();
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int r = 0; r < MazeModel.SIZE; r++)
            for (int c = 0; c < MazeModel.SIZE; c++) raw[r][c] = 1;
        raw[10][11] = 0;
        state.maze = new MazeModel(raw);

        PlayerRoute route = new MonsterAwareRoutePlanner().route(state, new Cell(10, 10), new Cell(10, 14));

        assertNotNull(route);
        assertEquals(new Cell(10, 10), route.cells().get(0));
        assertEquals(new Cell(10, 14), route.cells().get(route.size() - 1));
        assertTrue(route.size() > 1);
    }
}
