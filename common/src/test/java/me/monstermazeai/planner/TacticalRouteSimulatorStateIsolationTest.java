package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.player.Action;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TacticalRouteSimulatorStateIsolationTest {
    private static MazeModel openMaze() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int r = 0; r < MazeModel.SIZE; r++) {
            for (int c = 0; c < MazeModel.SIZE; c++) raw[r][c] = 1;
        }
        return new MazeModel(raw);
    }

    @Test
    void tacticalSimulationDoesNotMutateSourceMazeWhenPadDecayRuns() {
        GameState source = new GameState();
        source.maze = openMaze();
        source.player.x = 0.5;
        source.player.z = 0.5;
        source.player.grounded = true;
        source.oldPads.add(new Cell(0, 10));
        source.oldPadDecaySeconds.put(new Cell(0, 10), 1);

        Cell pad = new Cell(0, 10);
        for (int column = 8; column <= 12; column++) {
            source.maze.setPadSurface(0, column, true);
        }

        PlayerRoute route = new PlayerRoute(java.util.stream.IntStream.rangeClosed(0, 30)
                .mapToObj(c -> new Cell(0, c))
                .toList());

        long signatureBefore = source.maze.dynamicSignature();

        new TacticalRouteSimulator().simulate(
                source, route, new Cell(0, 30), false, 0);

        assertEquals(signatureBefore, source.maze.dynamicSignature(),
                "tactical progression must mutate only isolated branch mazes");
        assertTrue(source.oldPads.contains(pad));
        assertEquals(1, source.oldPadDecaySeconds.get(pad));
    }
    @Test
    void liveTacticalEntryUsesCurrentWaypointInsteadOfRouteOrigin() {
        GameState source = new GameState();
        source.maze = openMaze();
        source.player.x = 1.5;
        source.player.z = 2.5;
        source.player.yaw = 0.0F;
        source.player.grounded = true;

        PlayerRoute route = new PlayerRoute(List.of(
                new Cell(0, 0),
                new Cell(0, 1),
                new Cell(0, 2),
                new Cell(1, 2),
                new Cell(2, 2)));

        Action action = new TacticalRouteSimulator().nextAction(
                source, route, 4, new Cell(2, 2), false, 0);

        assertEquals(-30.0F, action.yawDelta(), 1.0e-6F,
                "mid-route tactical entry must steer toward the current segment, not the route origin");
    }


}
