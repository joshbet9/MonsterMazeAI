package me.monstermazeai.runtime;

import me.monstermazeai.adapter.LegacyAction;
import me.monstermazeai.adapter.LegacyProtocol;
import me.monstermazeai.adapter.LegacyWorldObservation;
import me.monstermazeai.planner.FirstPadMovementController;
import me.monstermazeai.adapter.ObservationWorldModel;
import me.monstermazeai.collision.CollisionModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.physics.LegacyMazePhysics;
import me.monstermazeai.planner.BeamSearchPlanner;
import me.monstermazeai.planner.Heuristic;
import me.monstermazeai.planner.LiveObjectiveController;
import me.monstermazeai.planner.MazeAwareRecedingHorizonController;
import me.monstermazeai.planner.RobustLiveController;
import me.monstermazeai.player.Action;
import me.monstermazeai.sim.Simulator;
import me.monstermazeai.telemetry.ReplayRecorder;
import me.monstermazeai.telemetry.TelemetryEvent;
import me.monstermazeai.telemetry.TelemetryRecorder;

import java.nio.file.Paths;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Random;

public final class AiSidecarMain {
    private AiSidecarMain() {}

    public static void main(String[] args) throws Exception {
        DataInputStream in = new DataInputStream(new BufferedInputStream(System.in));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(System.out));
        FirstPadMovementController firstPadController = null;
        TelemetryRecorder telemetry = null;
        ReplayRecorder replay = null;
        String telemetryPath = System.getProperty("monstermazeai.telemetry");
        String replayPath = System.getProperty("monstermazeai.replay");
        if (telemetryPath == null) telemetryPath = System.getenv("MONSTERMAZE_AI_TELEMETRY");
        if (replayPath == null) replayPath = System.getenv("MONSTERMAZE_AI_REPLAY");
        if (telemetryPath != null && !telemetryPath.isEmpty()) telemetry = new TelemetryRecorder(Paths.get(telemetryPath));
        if (replayPath != null && !replayPath.isEmpty()) replay = new ReplayRecorder(Paths.get(replayPath));

        long observationCount = 0L;
        long lastDiagnosticTick = Long.MIN_VALUE;

        while (true) {
            LegacyWorldObservation observation;
            try {
                observation = LegacyProtocol.readObservation(in);
            } catch (java.io.EOFException end) {
                return;
            }

            observationCount++;
            LegacyAction result = LegacyAction.IDLE;
            long decisionStart = System.nanoTime();
            GameState telemetryState = null;
            try {
                GameState state = ObservationWorldModel.from(observation);
                telemetryState = state;
                boolean decisionReady = state.inMonsterMaze && state.alive && !state.completed && state.maze != null
                        && state.activePadRow >= 0 && state.activePadColumn >= 0;

                if (observationCount == 1 || observation.worldTick != lastDiagnosticTick) {
                    System.err.println("[MonsterMazeAI] OBS tick=" + observation.worldTick
                            + " count=" + observationCount
                            + " rawInMaze=" + observation.inMonsterMaze
                            + " mazeDetected=" + observation.mazeDetected
                            + " stateInMaze=" + state.inMonsterMaze
                            + " alive=" + state.alive
                            + " completed=" + state.completed
                            + " maze=" + (state.maze != null)
                            + " pattern=" + state.mazePattern
                            + " player=" + state.player.x + "," + state.player.y + "," + state.player.z
                            + " vel=" + state.player.vx + "," + state.player.vy + "," + state.player.vz
                            + " yaw=" + state.player.yaw
                            + " grounded=" + state.player.grounded
                            + " pad=" + state.activePadRow + "," + state.activePadColumn
                            + " padReached=" + state.padReached
                            + " phase=" + state.phaseTicksRemaining
                            + " stage=" + state.stage
                            + " monsters=" + state.monsters.size());
                    lastDiagnosticTick = observation.worldTick;
                }

                if (decisionReady) {
                    if (firstPadController == null) {
                        firstPadController = new FirstPadMovementController();
                        System.err.println("[MonsterMazeAI] FIRST_PAD_MOVEMENT_ONLY initialized at tick="
                                + observation.worldTick
                                + " source=MonsterMaze 1.8 physical-floor + jump-spam parity");
                    }

                    Action action = firstPadController.nextAction(state);
                    result = new LegacyAction(action.forward(), action.strafe(), action.jump(),
                            action.sprint(), action.yawDelta(), false);

                    if (observationCount <= 3 || observationCount % 20 == 0
                            || action.yawDelta() != 0.0F) {
                        long decisionMicros = (System.nanoTime() - decisionStart) / 1000L;
                        System.err.println("[MonsterMazeAI] FIRST_PAD_DECISION tick=" + observation.worldTick
                                + " legacyOut=" + describe(result)
                                + " detail=" + firstPadController.lastDecisionDetail()
                                + " routeSize=" + firstPadController.routeSize()
                                + " segment=" + firstPadController.segmentIndex()
                                + " decisionUs=" + decisionMicros
                                + " localMonsters=" + state.monsters.size()
                                + " mode=movement-only");
                    }
                } else if (firstPadController != null) {
                    firstPadController.reset();
                    System.err.println("[MonsterMazeAI] FIRST_PAD_MOVEMENT reset by gate at tick="
                            + observation.worldTick);
                }
            } catch (RuntimeException failure) {
                if (agent != null) agent.reset();
                System.err.println("[MonsterMazeAI] sidecar decision failed: "
                        + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                failure.printStackTrace(System.err);
            }

            long latency = System.nanoTime() - decisionStart;
            if (telemetry != null && telemetryState != null) try {
                telemetry.record(new TelemetryEvent(observation.worldTick, latency, telemetryState, result,
                        result.useAbility ? "strategic-threat-response" : ""));
            } catch (java.io.IOException e) {
                System.err.println("[MonsterMazeAI] telemetry write failed: " + e.getMessage());
            }
            if (replay != null) try {
                replay.record(observation, result);
            } catch (java.io.IOException e) {
                System.err.println("[MonsterMazeAI] replay write failed: " + e.getMessage());
            }
            LegacyProtocol.writeAction(out, result);
            out.flush();
        }
    }

    private static String describe(LegacyAction action) {
        return "f=" + action.forward
                + ",s=" + action.strafe
                + ",jump=" + action.jump
                + ",sprint=" + action.sprint
                + ",yawDelta=" + action.yawDelta
                + ",ability=" + action.useAbility;
    }
}
