package me.monstermazeai.maze;

import me.monstermazeai.game.GameState;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.kit.Kit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalThreatDetourPlannerTest {
    @Test
    void detoursAroundImmediateRouteThreatAndRejoinsOriginalRoute() {
        GameState state = stateWithOpenCorridor();
        MonsterState monster = new MonsterState(1, 0.5, 0.0, 3.5);
        state.monsters.add(monster);

        PlayerRoute original = new PlayerRoute(List.of(
                new Cell(0, 0), new Cell(0, 1), new Cell(0, 2),
                new Cell(0, 3), new Cell(0, 4), new Cell(0, 5),
                new Cell(0, 6), new Cell(0, 7)));

        PlayerRoute detour = new LocalThreatDetourPlanner()
                .findDetour(state, original, 1, new Cell(0, 0));

        assertNotNull(detour);
        assertTrue(detour.cells().get(0).equals(new Cell(0, 0)));
        assertTrue(detour.cells().get(1).equals(new Cell(0, 1)),
                "the first edge must preserve the current heading");
        assertTrue(detour.cells().contains(new Cell(2, 3)),
                "the detour should actually leave the threatened corridor");
        assertTrue(detour.cells().contains(new Cell(0, 5))
                        || detour.cells().contains(new Cell(0, 6)),
                "the route should rejoin the original corridor beyond the threat");
    }

    @Test
    void ignoresThreatOutsideShortRouteHorizon() {
        GameState state = stateWithOpenCorridor();
        MonsterState monster = new MonsterState(2, 0.5, 0.0, 20.5);
        state.monsters.add(monster);

        PlayerRoute original = new PlayerRoute(List.of(
                new Cell(0, 0), new Cell(0, 1), new Cell(0, 2),
                new Cell(0, 3), new Cell(0, 4), new Cell(0, 5),
                new Cell(0, 6), new Cell(0, 7)));

        assertNull(new LocalThreatDetourPlanner()
                .findDetour(state, original, 1, new Cell(0, 0)));
    }

    private static GameState stateWithOpenCorridor() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int row = 0; row <= 4; row++) {
            for (int col = 0; col <= 8; col++) raw[row][col] = 1;
        }

        GameState state = new GameState();
        state.maze = new MazeModel(raw);
        state.kit = Kit.MAVERICK;
        state.player.x = 0.5;
        state.player.z = 0.5;
        state.player.grounded = true;
        return state;
    }
}
