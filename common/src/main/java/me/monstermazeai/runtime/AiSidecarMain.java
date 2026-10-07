package me.monstermazeai.runtime;

import me.monstermazeai.adapter.LegacyAction;
import me.monstermazeai.adapter.LegacyProtocol;
import me.monstermazeai.adapter.LegacyWorldObservation;
import me.monstermazeai.planner.LiveObjectiveController;
import me.monstermazeai.planner.MazeAwareRecedingHorizonController;
import me.monstermazeai.planner.RobustLiveController;
import me.monstermazeai.adapter.ObservationWorldModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.player.Action;
import me.monstermazeai.player.AiProfile;
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
        long bodyRushUntilTick = Long.MIN_VALUE;
        long lastBodyRushContactTick = Long.MIN_VALUE;

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
                            + " mode=" + state.mode
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
                    /*
                     * Body Rush is a server-side timed state. The inventory only
                     * exposes remaining activations, not whether the current
                     * 10-second immunity window is active. Because the AI itself
                     * is the sole source of the activation input, mirror that
                     * authoritative action into the next observations.
                     */
                    if (state.kit == me.monstermazeai.kit.Kit.BODY_BUILDER) {
                        if (bodyRushUntilTick > state.tick) {
                            state.ability.activeUntilTick = bodyRushUntilTick;
                        } else {
                            state.ability.activeUntilTick = 0L;
                            bodyRushUntilTick = Long.MIN_VALUE;
                        }

                        /*
                         * Body Rush contact launches the mob upward with the
                         * source +1.0 vertical velocity and shortens the active
                         * window by 40 ticks. The launched monster is observable
                         * on the following tick, so apply the same penalty once.
                         */
                        if (bodyRushUntilTick > state.tick) {
                            for (LegacyWorldObservation.Monster monster : observation.monsters) {
                                double dx = monster.x - observation.player.x;
                                double dy = monster.y - observation.player.y;
                                double dz = monster.z - observation.player.z;
                                if (dx * dx + dy * dy + dz * dz > 2.25D) continue;
                                if (monster.vy < 0.80D) continue;
                                if (state.tick == lastBodyRushContactTick) continue;
                                bodyRushUntilTick = Math.max(state.tick, bodyRushUntilTick - 40L);
                                lastBodyRushContactTick = state.tick;
                                state.ability.activeUntilTick = bodyRushUntilTick;
                                break;
                            }
                        }
                    }

                    // Non-Jumper players deliberately hold jump for the source's
                    // "speeding" mechanic. Jumpers may jump only while a charge remains.
                    boolean allowJump = state.kit != me.monstermazeai.kit.Kit.JUMPER
                            || state.player.jumpCharges > 0;
                    Action action = fullRoutingAgent.decide(state, allowJump);
                    result = new LegacyAction(action.forward(), action.strafe(), action.jump(),
                            action.sprint(), action.yawDelta(), action.useAbility());

                    if (action.useAbility() && state.kit == me.monstermazeai.kit.Kit.BODY_BUILDER) {
                        bodyRushUntilTick = state.tick + 200L;
                        lastBodyRushContactTick = Long.MIN_VALUE;
                    }

                    if (observationCount <= 3 || observationCount % 20 == 0
                            || action.yawDelta() != 0.0F
                            || action.useAbility()) {
                        long decisionMicros = (System.nanoTime() - decisionStart) / 1000L;
                        System.err.println("[MonsterMazeAI] FULL_ROUTING_DECISION tick=" + observation.worldTick
                                + " legacyOut=" + describe(result)
                                + " detail=" + fullRoutingAgent.lastDecisionDetail()
                                + " decisionUs=" + decisionMicros
                                + " localMonsters=" + state.monsters.size()
                                + " mode=full-routing");
                    }
                } else {
                    bodyRushUntilTick = Long.MIN_VALUE;
                    lastBodyRushContactTick = Long.MIN_VALUE;
                    fullRoutingAgent.reset();
                    System.err.println("[MonsterMazeAI] FULL_ROUTING reset by gate at tick="
                            + observation.worldTick);
                }
            } catch (RuntimeException failure) {
                fullRoutingAgent.reset();
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
