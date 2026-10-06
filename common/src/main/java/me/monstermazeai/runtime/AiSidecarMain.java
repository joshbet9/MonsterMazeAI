package me.monstermazeai.runtime;

import me.monstermazeai.adapter.LegacyAction;
import me.monstermazeai.adapter.LegacyProtocol;
import me.monstermazeai.adapter.LegacyWorldObservation;
import me.monstermazeai.planner.LiveObjectiveController;
import me.monstermazeai.planner.MazeAwareRecedingHorizonController;
import me.monstermazeai.planner.RobustLiveController;
import me.monstermazeai.adapter.ObservationWorldModel;
import me.monstermazeai.player.AiProfile;
import me.monstermazeai.game.GameState;
import me.monstermazeai.player.Action;
import me.monstermazeai.telemetry.ReplayRecorder;
import me.monstermazeai.telemetry.TelemetryEvent;
import me.monstermazeai.telemetry.TelemetryRecorder;

import java.nio.file.Paths;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

public final class AiSidecarMain {
    private AiSidecarMain() {}

    public static void main(String[] args) throws Exception {
        DataInputStream in = new DataInputStream(new BufferedInputStream(System.in));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(System.out));
        AutonomousMonsterMazeAgent fullRoutingAgent = new AutonomousMonsterMazeAgent(
                new RobustLiveController(
                        new LiveObjectiveController(
                                new MazeAwareRecedingHorizonController(1, AiProfile.HIGH_SKILL))));
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