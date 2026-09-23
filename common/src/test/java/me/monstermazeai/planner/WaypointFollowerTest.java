package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.game.Mode;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.physics.LegacyMazePhysics;
import me.monstermazeai.player.Action;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WaypointFollowerTest {
    private static MazeModel openMaze() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int r = 0; r < MazeModel.SIZE; r++)
            for (int c = 0; c < MazeModel.SIZE; c++)
                raw[r][c] = 1;
        return new MazeModel(raw);
    }

    @Test
    void routeUsesCellCentresAndPlayerCanCrossDisabledFloor() {
        MazeModel maze = openMaze();
        maze.setDisabled(1, 1, true);

        PlayerRoute route = PlayerRoute.between(
                maze, new Cell(0, 1), new Cell(2, 1));

        assertEquals(List.of(
                new Cell(0, 1), new Cell(1, 1), new Cell(2, 1)), route.cells());
        assertEquals(1.5, route.targetX(1));
        assertEquals(1.5, route.targetZ(1));
    }

    @Test
    void followerProducesPhysicalActionsWithoutCollisionLogic() {
        MazeModel maze = openMaze();
        PlayerRoute route = PlayerRoute.between(
                maze, new Cell(0, 0), new Cell(0, 4));

        GameState state = new GameState();
        state.mode = Mode.MODERN;
        state.maze = maze;
        state.player.x = 0.5;
        state.player.z = 0.5;
        state.player.y = GameState.PATH_Y;
        state.player.grounded = true;

        WaypointFollower follower = new WaypointFollower();
        LegacyMazePhysics physics = new LegacyMazePhysics();

        for (int i = 0; i < 100 && !follower.finished(route, state); i++) {
            Action action = follower.nextAction(route, state, false);
            physics.tick(state.player, action);
        }

        assertTrue(follower.finished(route, state),
                "Physics-driven route follower did not reach the final waypoint.");
        assertEquals(4.5, state.player.z, 0.35);
    }

    @Test
    void followerAdvancesPastAlreadyReachedWaypoints() {
        MazeModel maze = openMaze();
        PlayerRoute route = PlayerRoute.between(
                maze, new Cell(0, 0), new Cell(0, 3));

        GameState state = new GameState();
        state.maze = maze;
        state.player.x = 0.5;
        state.player.z = 2.49;
        state.player.grounded = true;

        WaypointFollower follower = new WaypointFollower(0.18);
        assertFalse(follower.finished(route, state));

        follower.nextAction(route, state, false);

        assertTrue(follower.waypointIndex() >= 2,
                "Follower should skip waypoints already passed within tolerance.");
    }
}
