package me.monstermazeai.minecraft.v18;

import me.monstermazeai.adapter.LegacyAction;
import me.monstermazeai.adapter.LegacyWorldObservation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bounded per-game telemetry for post-game GPT analysis.
 *
 * This deliberately records facts rather than attempting to interpret them:
 * positions, velocity, pads/stages, actions, movement anomalies and the exact
 * server end message. The final block is compact enough to paste into ChatGPT
 * while retaining the important causal timeline.
 */
public final class GameRunSummaryRecorder {
    private static final int MAX_EVENTS = 400;
    private static final int MAX_SAMPLES = 80;

    private long gameNumber;
    private boolean active;
    private long startTick;
    private long endTick;
    private String endReason = "UNKNOWN";
    private int observationTicks;
    private int mazeDetectedTicks;
    private int inMazeTicks;
    private int aliveTicks;
    private int completedTicks;
    private int actionTicks;
    private int forwardTicks;
    private int sprintTicks;
    private int jumpTicks;
    private int abilityTicks;
    private int yawTicks;
    private int nonIdleTicks;
    private double maxHorizontalSpeed;
    private double maxTickDisplacement;
    private double maxAbsYawDelta;
    private double maxAbsVerticalOffset;
    private double previousX = Double.NaN;
    private double previousZ = Double.NaN;
    private int lastStage = Integer.MIN_VALUE;
    private int lastPadRow = Integer.MIN_VALUE;
    private int lastPadColumn = Integer.MIN_VALUE;
    private boolean lastPadReached;
    private String lastAction = "IDLE";
    private static final int MAX_CRITICAL_EVENTS = 200;

    private final List<String> events = new ArrayList<String>();
    private final List<String> criticalEvents = new ArrayList<String>();
    private final List<String> samples = new ArrayList<String>();

    public void reset() {
        active = false;
        startTick = 0L;
        endTick = 0L;
        endReason = "UNKNOWN";
        observationTicks = 0;
        mazeDetectedTicks = 0;
        inMazeTicks = 0;
        aliveTicks = 0;
        completedTicks = 0;
        actionTicks = 0;
        forwardTicks = 0;
        sprintTicks = 0;
        jumpTicks = 0;
        abilityTicks = 0;
        yawTicks = 0;
        nonIdleTicks = 0;
        maxHorizontalSpeed = 0.0D;
        maxTickDisplacement = 0.0D;
        maxAbsYawDelta = 0.0D;
        maxAbsVerticalOffset = 0.0D;
        previousX = Double.NaN;
        previousZ = Double.NaN;
        lastStage = Integer.MIN_VALUE;
        lastPadRow = Integer.MIN_VALUE;
        lastPadColumn = Integer.MIN_VALUE;
        lastPadReached = false;
        lastAction = "IDLE";
        events.clear();
        criticalEvents.clear();
        samples.clear();
    }

    public void begin(long tick) {
        if (active) return;
        gameNumber++;
        active = true;
        startTick = tick;
        endTick = tick;
        addEvent(tick, "GAME_START");
    }

    public void observe(LegacyWorldObservation state, LegacyAction action) {
        if (state == null) return;
        if (!active) begin(state.worldTick);

        observationTicks++;
        endTick = state.worldTick;
        if (state.inMonsterMaze) inMazeTicks++;
        if (state.mazeDetected) mazeDetectedTicks++;
        if (state.alive) aliveTicks++;
        if (state.completed) completedTicks++;

        double dx = Double.isNaN(previousX) ? 0.0D : state.player.x - previousX;
        double dz = Double.isNaN(previousZ) ? 0.0D : state.player.z - previousZ;
        previousX = state.player.x;
        previousZ = state.player.z;

        double displacement = Math.hypot(dx, dz);
        double speed = Math.hypot(state.player.vx, state.player.vz);
        maxTickDisplacement = Math.max(maxTickDisplacement, displacement);
        maxHorizontalSpeed = Math.max(maxHorizontalSpeed, speed);
        maxAbsVerticalOffset = Math.max(maxAbsVerticalOffset,
                Math.abs(state.center == null ? state.player.y : state.player.y - state.center.y));

        if (state.stage != lastStage) {
            if (lastStage != Integer.MIN_VALUE) {
                addEvent(state.worldTick, "STAGE_CHANGE "
                        + lastStage + "->" + state.stage);
            } else {
                addEvent(state.worldTick, "STAGE=" + state.stage);
            }
            lastStage = state.stage;
        }

        if (state.pad != null
                && (state.pad.row != lastPadRow
                || state.pad.column != lastPadColumn
                || state.pad.reached != lastPadReached)) {
            addEvent(state.worldTick, "PAD="
                    + state.pad.row + "," + state.pad.column
                    + " reached=" + state.pad.reached);
            lastPadRow = state.pad.row;
            lastPadColumn = state.pad.column;
            lastPadReached = state.pad.reached;
        }

        if (speed >= 0.35D || displacement >= 0.45D) {
            addEvent(state.worldTick, "IMPULSE"
                    + " speed=" + format(speed)
                    + " displacement=" + format(displacement)
                    + " motion=" + format(state.player.vx)
                    + "," + format(state.player.vz));
        }

        String actionName = actionName(action);
        if (!actionName.equals(lastAction)) {
            addEvent(state.worldTick, "ACTION=" + actionName);
            lastAction = actionName;
        }

        if (action != null) {
            actionTicks++;
            if (Math.abs(action.forward) > 0.01D) forwardTicks++;
            if (action.sprint) sprintTicks++;
            if (action.jump) jumpTicks++;
            if (action.useAbility) abilityTicks++;
            if (Math.abs(action.yawDelta) > 0.01F) yawTicks++;
            if (!"IDLE".equals(actionName)) nonIdleTicks++;
            maxAbsYawDelta = Math.max(maxAbsYawDelta, Math.abs(action.yawDelta));
        }

        /*
         * Keep periodic state samples. Event lines capture changes; samples
         * capture what was actually happening between changes.
         */
        if (observationTicks == 1 || observationTicks % 20 == 0) {
            addSample(state, action, speed, displacement);
        }
    }

    public String finish(long tick, String reason) {
        if (!active) return null;
        endTick = tick;
        endReason = reason == null || reason.length() == 0 ? "UNKNOWN" : reason;
        addEvent(tick, "GAME_END reason=" + endReason);

        String report = buildReport();
        active = false;
        return report;
    }

    public void controllerEvent(long tick, String event) {
        if (!active || event == null || event.length() == 0) return;
        String clean = event.replace("\n", " ").replace("\r", " ");
        /*
         * Controller events are the causal layer: they explain what the
         * movement policy decided, not merely what the Minecraft world looked
         * like. Strip the common console prefix to keep the GPT report compact.
         */
        if (clean.startsWith("[MonsterMazeAI/1.8] ")) {
            clean = clean.substring("[MonsterMazeAI/1.8] ".length());
        }
        addEvent(tick, "CTRL " + clean);
    }

    public boolean isActive() {
        return active;
    }

    private String buildReport() {
        long duration = Math.max(0L, endTick - startTick);
        StringBuilder out = new StringBuilder(7000);

        out.append("\n========== MONSTER MAZE GPT GAME SUMMARY ==========\n");
        out.append("game=").append(gameNumber)
                .append(" startTick=").append(startTick)
                .append(" endTick=").append(endTick)
                .append(" durationTicks=").append(duration)
                .append(" durationSeconds=").append(format(duration / 20.0D)).append("\n");
        out.append("endReason=").append(endReason).append("\n");
        out.append("observations=").append(observationTicks)
                .append(" inMazeTicks=").append(inMazeTicks)
                .append(" mazeDetectedTicks=").append(mazeDetectedTicks)
                .append(" aliveTicks=").append(aliveTicks)
                .append(" completedTicks=").append(completedTicks).append("\n");
        out.append("movementTicks=").append(nonIdleTicks)
                .append(" forwardTicks=").append(forwardTicks)
                .append(" sprintTicks=").append(sprintTicks)
                .append(" jumpTicks=").append(jumpTicks)
                .append(" abilityTicks=").append(abilityTicks)
                .append(" yawTicks=").append(yawTicks).append("\n");
        out.append("maxHorizontalSpeed=").append(format(maxHorizontalSpeed))
                .append(" maxTickDisplacement=").append(format(maxTickDisplacement))
                .append(" maxAbsYawDelta=").append(format(maxAbsYawDelta))
                .append(" maxAbsVerticalOffset=").append(format(maxAbsVerticalOffset)).append("\n");

        out.append("CRITICAL_TIMELINE:\n");
        for (String event : criticalEvents) {
            out.append("  ").append(event).append("\n");
        }

        out.append("EVENT_TIMELINE:\n");
        for (String event : events) {
            out.append("  ").append(event).append("\n");
        }

        out.append("PERIODIC_SAMPLES:\n");
        for (String sample : samples) {
            out.append("  ").append(sample).append("\n");
        }

        out.append("===================================================\n");
        return out.toString();
    }

    private void addEvent(long tick, String event) {
        String line = "tick=" + tick + " " + event;

        /*
         * The high-frequency event stream is intentionally bounded, but
         * route/phase failures must never disappear just because the movement
         * controller produced many ACTION/IMPULSE lines first. Keep a second
         * small stream for causal events that are required to diagnose a run.
         */
        if (isCriticalEvent(event) && criticalEvents.size() < MAX_CRITICAL_EVENTS) {
            criticalEvents.add(line);
        }

        if (events.size() >= MAX_EVENTS) {
            if (events.size() == MAX_EVENTS) {
                events.add("... event limit reached; critical timeline retained separately");
            }
            return;
        }
        events.add(line);
    }

    private static boolean isCriticalEvent(String event) {
        return event.contains("PAD TRANSITION")
                || event.contains("PAD REACHED")
                || event.contains("PAD EXIT RECOVERY")
                || event.contains("PAD ROUTE")
                || event.contains("DYNAMIC ROUTE EXHAUSTED")
                || event.contains("STATIC FALLBACK")
                || event.contains("ROUTE REPLAN")
                || event.contains("MOB ROUTE")
                || event.contains("PHYSICAL ROUTE")
                || event.contains("RECOVERY")
                || event.contains("KNOCKBACK")
                || event.contains("GAP COMMIT")
                || event.contains("GAP TAKEOFF")
                || event.contains("GAP EXECUTE")
                || event.contains("GAP LANDING")
                || event.contains("GAP MISSED")
                || event.contains("NO_ROUTE")
                || event.contains("ROUTE END WITHOUT PAD")
                || event.contains("PAD TRANSITION ROUTE FAILED");
    }

    private void addSample(LegacyWorldObservation state, LegacyAction action,
                           double speed, double displacement) {
        if (samples.size() >= MAX_SAMPLES) return;
        String pad = state.pad == null
                ? "none"
                : state.pad.row + "," + state.pad.column
                    + "/reached=" + state.pad.reached;
        String center = state.center == null
                ? "none"
                : state.center.x + "," + state.center.y + "," + state.center.z;
        samples.add("tick=" + state.worldTick
                + " stage=" + state.stage
                + " inMaze=" + state.inMonsterMaze
                + " detected=" + state.mazeDetected
                + " alive=" + state.alive
                + " player=" + format(state.player.x) + "," + format(state.player.y)
                    + "," + format(state.player.z)
                + " motion=" + format(state.player.vx) + "," + format(state.player.vy)
                    + "," + format(state.player.vz)
                + " speed=" + format(speed)
                + " displacement=" + format(displacement)
                + " yaw=" + format(state.player.yaw)
                + " grounded=" + state.player.grounded
                + " center=" + center
                + " pad=" + pad
                + " action=" + actionName(action));
    }

    private static String actionName(LegacyAction action) {
        if (action == null) return "NULL";
        if (Math.abs(action.forward) < 0.01D
                && Math.abs(action.strafe) < 0.01D
                && !action.jump
                && !action.sprint
                && Math.abs(action.yawDelta) < 0.01F
                && !action.useAbility) {
            return "IDLE";
        }
        return "F=" + format(action.forward)
                + ",S=" + format(action.strafe)
                + ",J=" + action.jump
                + ",SP=" + action.sprint
                + ",Y=" + format(action.yawDelta)
                + ",A=" + action.useAbility;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
