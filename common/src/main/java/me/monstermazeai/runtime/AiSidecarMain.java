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
        AutonomousMonsterMazeAgent agent = null;
        TelemetryRecorder telemetry = null;
        ReplayRecorder replay = null;
        String telemetryPath = System.getProperty("monstermazeai.telemetry");
        String replayPath = System.getProperty("monstermazeai.replay");
        if (telemetryPath == null) telemetryPath = System.getenv("MONSTERMAZE_AI_TELEMETRY");
        if (replayPath == null) replayPath = System.getenv("MONSTERMAZE_AI_REPLAY");
        if (telemetryPath != null && !telemetryPath.isEmpty()) telemetry = new TelemetryRecorder(Paths.get(telemetryPath));
        if (replayPath != null && !replayPath.isEmpty()) replay = new ReplayRecorder(Paths.get(replayPath));

        while (true) {
            LegacyWorldObservation observation;
            try {
                observation = LegacyProtocol.readObservation(in);
            } catch (java.io.EOFException end) {
                return;
            }

            LegacyAction result = LegacyAction.IDLE;
            long decisionStart = System.nanoTime();
            GameState telemetryState = null;
            try {
                GameState state = ObservationWorldModel.from(observation);
                telemetryState = state;
                if (state.inMonsterMaze && state.alive && !state.completed && state.maze != null
                        && state.activePadRow >= 0 && state.activePadColumn >= 0) {
                    if (agent == null) {
                        MazeModel maze = state.maze;
                        Simulator simulator = new Simulator(
                                new LegacyMazePhysics(),
                                new MonsterSimulator(maze,
                                        new Random(observation.worldTick ^ 0x4D4D4159L), 0.0),
                                new CollisionModel());
                        LiveObjectiveController objective = new LiveObjectiveController(
                                new MazeAwareRecedingHorizonController(
                                        new BeamSearchPlanner(simulator, new Heuristic(), 8, 4), 1));
                        agent = new AutonomousMonsterMazeAgent(new RobustLiveController(objective));
                    }
                    Action action = agent.decide(state, true);
                    result = new LegacyAction(action.forward(), action.strafe(), action.jump(),
                            action.sprint(), action.yawDelta(), action.useAbility());
                } else if (agent != null) {
                    agent.reset();
                }
            } catch (RuntimeException failure) {
                if (agent != null) agent.reset();
                System.err.println("[MonsterMazeAI] sidecar decision failed: "
                        + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }

            long latency = System.nanoTime() - decisionStart;
            if (telemetry != null && telemetryState != null) try { telemetry.record(new TelemetryEvent(observation.worldTick, latency, telemetryState, result, result.useAbility ? "strategic-threat-response" : "")); } catch (java.io.IOException e) { System.err.println("[MonsterMazeAI] telemetry write failed: " + e.getMessage()); }
            if (replay != null) try { replay.record(observation, result); } catch (java.io.IOException e) { System.err.println("[MonsterMazeAI] replay write failed: " + e.getMessage()); }
            LegacyProtocol.writeAction(out, result);
            out.flush();
        }
    }
}