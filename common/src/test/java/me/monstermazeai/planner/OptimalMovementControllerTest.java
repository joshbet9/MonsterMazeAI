package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.maze.MonsterAwareRoutePlanner;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.physics.LegacyMazePhysics;
import me.monstermazeai.sim.Simulator;
import me.monstermazeai.collision.CollisionModel;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.Action;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class OptimalMovementControllerTest {
    private static MazeModel openMaze() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int r = 0; r < MazeModel.SIZE; r++)
            for (int c = 0; c < MazeModel.SIZE; c++) raw[r][c] = 1;
        return new MazeModel(raw);
    }

    private static MazeModel narrowMaze() {
        MazeModel maze = openMaze();
        for (int r = 0; r < MazeModel.SIZE; r++) for (int col = 0; col < MazeModel.SIZE; col++) {
            maze.setPhysicalFloor(r, col, r == 50 && col >= 48 && col <= 52);
        }
        return maze;
    }

    private static Simulator simulator(MazeModel maze) {
        return new Simulator(new LegacyMazePhysics(),
                new MonsterSimulator(maze, new Random(7), 0.0),
                new CollisionModel());
    }

    private static GameState state(Simulator simulator) {
        GameState s = new GameState();
        s.maze = openMaze();
        s.inMonsterMaze = true;
        s.alive = true;
        s.kit = Kit.REPULSOR;
        s.activePadRow = 50;
        s.activePadColumn = 56;
        s.player.x = 50.5;
        s.player.z = 50.5;
        s.player.grounded = true;
        s.player.yaw = 0.0F;
        return s;
    }

    @Test
    void routeAvoidsPredictedMonsterCorridor() {
        MazeModel maze = openMaze();
        Simulator sim = simulator(maze);
        GameState s = state(sim);
        s.player.health = 4.0;
        s.monsters.add(new MonsterState(1, 50.5, 0.0, 52.5));

        PlayerRoute route = new MonsterAwareRoutePlanner()
                .route(s, new Cell(50, 50), new Cell(50, 56));

        assertFalse(route.cells().contains(new Cell(50, 52)),
                "A directly occupied high-risk cell should not be preferred when a detour exists");
    }

    @Test
    void movementDoesNotAccelerateAcrossUnsafeEdge() {
        MazeModel maze = narrowMaze();
        Simulator sim = simulator(maze);
        GameState s = state(sim);
        s.maze = maze;
        s.activePadColumn = 48;
        s.player.x = 50.85;
        s.player.z = 50.5;
        s.player.vx = 0.0;
        s.player.yaw = -90.0F;

        Action action = new OptimalMovementController(sim, 0.65)
                .nextAction(s, new Cell(50, 48), true);

        GameState next = sim.forecast(s, action, 2, 1234L);
        assertTrue(next.alive);
        assertTrue(next.player.x < 51.0);
    }

    @Test
    void knockbackRecoveryTurnsBackIntoFloor() {
        MazeModel maze = narrowMaze();
        Simulator sim = simulator(maze);
        GameState s = state(sim);
        s.maze = maze;
        s.player.x = 50.82;
        s.player.z = 50.5;
        s.player.vx = 0.05;
        s.player.vz = 0.0;
        s.player.recentMobHitUntilTick = 20;
        s.tick = 1;

        KnockbackRecoveryController recovery = new KnockbackRecoveryController(sim);
        assertTrue(recovery.shouldRecover(s));

        Action action = recovery.nextAction(s, true);
        GameState next = sim.forecast(s, action, 3, 42L);

        assertTrue(next.alive);
        assertTrue(next.player.x < 51.0);
        assertTrue(s.maze.isPhysicalFloor((int) Math.floor(next.player.x),
                (int) Math.floor(next.player.z)));
    }

    @Test
    void jumperChargesAreConservedDuringNormalRouteTravel() {
        Simulator sim = simulator(openMaze());
        GameState jumper = state(sim);
        jumper.kit = Kit.JUMPER;
        jumper.player.jumpCharges = 5;

        Action action = new OptimalMovementController(sim, 0.65)
                .nextAction(jumper, new Cell(50, 56), true);

        assertFalse(action.jump(),
                "A normal route must not burn a finite Jumper charge immediately");
    }

    @Test
    void jumperChargeIsConsumedOncePerActualJump() {
        Simulator sim = simulator(openMaze());
        GameState jumper = state(sim);
        jumper.kit = Kit.JUMPER;
        jumper.player.jumpCharges = 5;

        sim.tick(jumper, new Action(1, 0, true, true, 0.0F, false));
        assertEquals(4, jumper.player.jumpCharges);

        sim.tick(jumper, new Action(1, 0, true, true, 0.0F, false));
        assertEquals(4, jumper.player.jumpCharges,
                "Airborne ticks must not consume additional Jumper charges");
    }

    @Test
    void nonJumperAndJumperBothHoldJumpForMovementOptimisation() {
        Simulator sim = simulator(openMaze());
        GameState normal = state(sim);
        normal.kit = Kit.REPULSOR;
        Action normalAction = new OptimalMovementController(sim, 0.65)
                .nextAction(normal, new Cell(50, 56), true);
        assertTrue(normalAction.jump());

        GameState jumper = state(sim);
        jumper.kit = Kit.JUMPER;
        jumper.player.jumpCharges = 0;
        Action jumperAction = new OptimalMovementController(sim, 0.65)
                .nextAction(jumper, new Cell(50, 56), true);
        assertTrue(jumperAction.jump());
    }
}
