package me.monstermazeai.runtime;

import me.monstermazeai.adapter.LegacyAction;
import me.monstermazeai.adapter.LegacyProtocol;
import me.monstermazeai.adapter.LegacyWorldObservation;
import me.monstermazeai.adapter.ObservationWorldModel;
import me.monstermazeai.collision.CollisionModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.physics.LegacyMazePhysics;
import me.monstermazeai.planner.BeamSearchPlanner;
import me.monstermazeai.planner.Heuristic;
import me.monstermazeai.planner.LiveTickController;
import me.monstermazeai.planner.MazeAwareRecedingHorizonController;
import me.monstermazeai.player.Action;
import me.monstermazeai.sim.Simulator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Random;

/**
 * Java-17 sidecar process used by the Java-8 Minecraft 1.8 client.
 *
 * stdin/stdout are a binary request/response stream. Never write diagnostics to
 * stdout: it is the protocol channel. Diagnostics should go to stderr.
 */
public final class AiSidecarMain {
    private AiSidecarMain() {}

    public static void main(String[] args) throws Exception {
        DataInputStream in = new DataInputStream(new BufferedInputStream(System.in));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(System.out));

        while (true) {
            LegacyWorldObservation observation;
            try {
                observation = LegacyProtocol.readObservation(in);
            } catch (java.io.EOFException end) {
                return;
            }

            LegacyAction result = LegacyAction.IDLE;
            try {
                GameState state = ObservationWorldModel.from(observation);
                if (state.inMonsterMaze && state.alive && !state.completed && state.maze != null
                        && state.activePadRow >= 0 && state.activePadColumn >= 0) {
                    MazeModel maze = state.maze;
                    Simulator simulator = new Simulator(
                            new LegacyMazePhysics(),
                            new MonsterSimulator(maze, new Random(observation.worldTick ^ 0x4D4D4159L), 0.0),
                            new CollisionModel());
                    LiveTickController controller = new LiveTickController(
                            new MazeAwareRecedingHorizonController(
                                    new BeamSearchPlanner(simulator, new Heuristic(), 8, 4), 1));
                    Action action = controller.nextAction(state, true);
                    result = new LegacyAction(action.forward(), action.strafe(), action.jump(),
                            action.sprint(), action.yawDelta(), action.useAbility());
                }
            } catch (RuntimeException failure) {
                System.err.println("[MonsterMazeAI] sidecar decision failed: " + failure.getClass().getSimpleName()
                        + ": " + failure.getMessage());
            }

            LegacyProtocol.writeAction(out, result);
            out.flush();
        }
    }
}
