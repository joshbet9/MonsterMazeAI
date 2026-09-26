package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.player.Action;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RobustLiveControllerTest {
    private static GameState live() {
        GameState s = new GameState();
        s.inMonsterMaze = true;
        s.alive = true;
        s.phaseTicksRemaining = 200;
        s.activePadRow = 55;
        s.activePadColumn = 50;
        s.maze = openMaze();
        s.player.x = 50.5;
        s.player.z = 50.5;
        s.player.grounded = true;
        s.kit = Kit.JUMPER;
        return s;
    }

    private static MazeModel openMaze() {
        int[][] cells = new int[99][99];
        for (int r = 0; r < 99; r++) for (int c = 0; c < 99; c++) cells[r][c] = 1;
        return new MazeModel(cells);
    }

    private static RobustLiveController controller() {
        MazeAwareRecedingHorizonController movement =
                new MazeAwareRecedingHorizonController(
                        new BeamSearchPlanner(
                                new me.monstermazeai.sim.Simulator(
                                        new me.monstermazeai.physics.LegacyMazePhysics(),
                                        new me.monstermazeai.monster.MonsterSimulator(openMaze(),
                                                new java.util.Random(1), 0),
                                        new me.monstermazeai.collision.CollisionModel()),
                                new Heuristic(), 4, 2), 1);
        return new RobustLiveController(new LiveObjectiveController(movement));
    }

    @Test void rejectsStaleObservation() {
        RobustLiveController c = controller();
        GameState s = live();
        s.tick = 10;
        assertNotEquals(Action.IDLE, c.nextAction(s, true));
        assertEquals(Action.IDLE, c.nextAction(s, true));
    }

    @Test void requestsJumpAfterPersistentStall() {
        RobustLiveController c = controller();
        GameState s = live();
        for (int i = 0; i < 8; i++) {
            s.tick = i + 1;
            s.player.x = 50.5;
            s.player.z = 50.5;
            Action a = c.nextAction(s, true);
            if (i < 7) assertFalse(a.jump());
        }
        s.tick = 9;
        s.player.x = 50.5;
        s.player.z = 50.5;
        assertTrue(c.nextAction(s, true).jump());
    }

    @Test void unknownPhaseStillAllowsMovement() {
        RobustLiveController c = controller();
        GameState s = live();
        s.tick = 1;
        s.phaseTicksRemaining = -1;

        assertNotEquals(Action.IDLE, c.nextAction(s, true));
    }

    @Test void invalidOrExpiredStateFailsClosed() {
        RobustLiveController c = controller();
        GameState s = live();
        s.tick = 1;
        assertNotEquals(Action.IDLE, c.nextAction(s, true));
        s.tick = 2;
        s.phaseTicksRemaining = 0;
        assertEquals(Action.IDLE, c.nextAction(s, true));
    }
}
