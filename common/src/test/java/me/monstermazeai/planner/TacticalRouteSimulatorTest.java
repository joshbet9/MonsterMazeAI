package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.player.Action;
import me.monstermazeai.kit.Kit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TacticalRouteSimulatorTest {
    private static MazeModel openMaze() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int row = 0; row < MazeModel.SIZE; row++) {
            for (int column = 0; column < MazeModel.SIZE; column++) {
                raw[row][column] = 1;
            }
        }
        return new MazeModel(raw);
    }

    @Test
    void routeFollowerCarriesForwardThroughRecoverableLargeHeadingError() {
        GameState state = new GameState();
        state.maze = openMaze();
        state.kit = Kit.MAVERICK;
        state.player.x = 0.5D;
        state.player.z = 0.5D;
        state.player.yaw = 90.0F;
        state.player.grounded = true;

        PlayerRoute route = new PlayerRoute(java.util.List.of(
                new Cell(0, 0),
                new Cell(0, 8)));

        Action action = new TacticalRouteSimulator().nextAction(
                state, route, new Cell(0, 4), false, 0);

        assertTrue(action.forward() > 0.0D,
                "tactical route evaluation must not introduce a synthetic turn-in-place stop");
        assertTrue(action.sprint(),
                "recoverable heading correction should retain sprint momentum");
        assertTrue(action.yawDelta() < 0.0F,
                "heading correction should turn toward the route");
    }
}
