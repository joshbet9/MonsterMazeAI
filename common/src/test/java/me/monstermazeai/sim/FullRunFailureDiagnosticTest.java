package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import me.monstermazeai.player.Action;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class FullRunFailureDiagnosticTest {
    private record Case(Mode mode, int pattern, Kit kit) {}
    @Test
    void printBehaviourReplayForSelectedCases() {
        Case[] cases = {
                new Case(Mode.MODERN, 1, Kit.JUMPER),
                new Case(Mode.MODERN, 1, Kit.SLOWBALLER),
                new Case(Mode.MODERN, 2, Kit.JUMPER),
                new Case(Mode.MODERN, 2, Kit.REPULSOR),
                new Case(Mode.MODERN, 3, Kit.JUMPER),
                new Case(Mode.MODERN, 3, Kit.BODY_BUILDER),
                new Case(Mode.MODERN, 3, Kit.REPULSOR),
                new Case(Mode.SPEED, 1, Kit.JUMPER),
                new Case(Mode.SPEED, 2, Kit.REPULSOR),
                new Case(Mode.SPEED, 3, Kit.JUMPER),
                new Case(Mode.SPEED, 2, Kit.MAVERICK)
        };

        String only = System.getProperty("monstermaze.diag.case", "").trim();

        for (Case c : cases) {
            String id = c.mode + ":P" + c.pattern + ":" + c.kit;
            if (!only.isEmpty() && !only.equalsIgnoreCase(id)) continue;

            AuthenticStage10SimulationTest.BehaviorTraceResult traced =
                    AuthenticStage10SimulationTest.runToEndWithBehaviorTrace(
                            c.pattern - 1, c.kit, AiProfile.HIGH_SKILL, c.mode);

            printCase(c, traced);
        }
    }

    private static void printCase(
            Case c,
            AuthenticStage10SimulationTest.BehaviorTraceResult traced) {
        AuthenticStage10SimulationTest.RunResult r = traced.result();
        List<AuthenticStage10SimulationTest.BehaviorTick> ticks = traced.ticks();

        System.out.println();
        System.out.println("===== BEHAVIOR_CASE mode=" + c.mode
                + " pattern=" + c.pattern
                + " kit=" + c.kit + " =====");
        System.out.println("RESULT maxStage=" + r.maxStage()
                + " ticks=" + r.ticks()
                + " health=" + r.health()
                + " firstFallTick=" + r.firstFallTick()
                + " terminalTick=" + r.terminalTick()
                + " terminalStage=" + r.terminalStage()
                + " terminalPad=" + r.terminalPadRow() + "," + r.terminalPadColumn()
                + " terminalOnPad=" + r.terminalOnPad());

        Map<String, Integer> behaviorCounts = new LinkedHashMap<>();
        Map<String, Integer> actionCounts = new LinkedHashMap<>();
        long jumps = 0;
        long abilities = 0;
        long zeroInput = 0;
        long stationary = 0;
        long stageChanges = 0;
        int previousStage = ticks.isEmpty() ? 1 : ticks.get(0).stage();
        int previousPadRow = ticks.isEmpty() ? -1 : ticks.get(0).activePadRow();
        int previousPadColumn = ticks.isEmpty() ? -1 : ticks.get(0).activePadColumn();
        String previousBehavior = "";

        for (AuthenticStage10SimulationTest.BehaviorTick t : ticks) {
            String behavior = behaviorClass(t.decision());
            behaviorCounts.merge(behavior, 1, Integer::sum);
            String actionKey = actionClass(t.action());
            actionCounts.merge(actionKey, 1, Integer::sum);
            if (t.action().jump()) jumps++;
            if (t.action().useAbility()) abilities++;
            if (Math.hypot(t.action().forward(), t.action().strafe()) < 1.0E-6) {
                zeroInput++;
                if (Math.hypot(t.postVx(), t.postVz()) < 0.05) stationary++;
            }

            boolean stageChanged = t.stage() != previousStage;
            boolean padChanged = t.activePadRow() != previousPadRow
                    || t.activePadColumn() != previousPadColumn;
            boolean behaviorChanged = !behavior.equals(previousBehavior);
            boolean significantAction = t.action().jump()
                    || t.action().useAbility()
                    || Math.abs(t.action().yawDelta()) > 0.01f;
            boolean heartbeat = t.tick() % 20 == 0;

            if (stageChanged) stageChanges++;
            if (stageChanged || padChanged || behaviorChanged || significantAction || heartbeat) {
                System.out.println(formatTick(t, behavior, stageChanged, padChanged));
            }

            previousStage = t.stage();
            previousPadRow = t.activePadRow();
            previousPadColumn = t.activePadColumn();
            previousBehavior = behavior;
        }

        System.out.println("COUNTS behavior=" + behaviorCounts);
        System.out.println("COUNTS action=" + actionCounts);
        System.out.println("COUNTS jumps=" + jumps
                + " abilities=" + abilities
                + " zeroInput=" + zeroInput
                + " stationary=" + stationary
                + " stageChanges=" + stageChanges);
    }

    private static String formatTick(
            AuthenticStage10SimulationTest.BehaviorTick t,
            String behavior,
            boolean stageChanged,
            boolean padChanged) {
        Action a = t.action();
        return "BEHAVIOR_TICK t=" + t.tick()
                + " stage=" + t.stage()
                + " pad=" + t.activePadRow() + "," + t.activePadColumn()
                + " pre=" + fmt(t.preX()) + "," + fmt(t.preY()) + "," + fmt(t.preZ())
                + " post=" + fmt(t.postX()) + "," + fmt(t.postY()) + "," + fmt(t.postZ())
                + " v=" + fmt(t.postVx()) + "," + fmt(t.postVy()) + "," + fmt(t.postVz())
                + " yaw=" + fmt(t.yaw())
                + " action=f" + fmt(a.forward())
                + ",s" + fmt(a.strafe())
                + ",j" + a.jump()
                + ",sp" + a.sprint()
                + ",yd" + fmt(a.yawDelta())
                + ",ab" + a.useAbility()
                + " class=" + behavior
                + (stageChanged ? " STAGE_CHANGE" : "")
                + (padChanged ? " PAD_CHANGE" : "")
                + " decision=" + compactDecision(t.decision());
    }

    private static String compactDecision(String decision) {
        if (decision == null) return "null";
        return decision.replace(' ', '_');
    }

    private static String behaviorClass(String decision) {
        if (decision == null || decision.isBlank()) return "NONE";
        String[] exact = {
                "MOB_HIT_RECOVERY", "FAST_RECOVERY_ROUTE", "BOOTSTRAP_ROUTE",
                "REACHED_SAFE_PAD", "NO_PHYSICAL_SUPPORT", "MOB_YIELD_GAP_HOLD",
                "MOB_YIELD_ALIGN", "MOB_YIELD", "GAP_MISSED_REPLAN",
                "GAP_LANDING_CONFIRMED", "GAP_LANDING_FAILED", "GAP_TAKEOFF",
                "GAP_EXECUTE", "GAP_SPEED_PULSE", "GAP_APPROACH", "GAP_ALIGN",
                "GAP_BRAKE", "CORNER_PREP", "CORNER_STAGE_COAST",
                "CORNER_STAGE_PUSH", "STEER_DRIVE", "LANE_RECOVERY",
                "EDGE_GUARD", "PASS", "NO_ROUTE"
        };
        for (String prefix : exact) {
            if (decision.startsWith(prefix) || decision.contains(" " + prefix)
                    || decision.contains(prefix + " ")) {
                return prefix;
            }
        }
        int space = decision.indexOf(' ');
        return space > 0 ? decision.substring(0, space) : decision;
    }

    private static String actionClass(Action a) {
        return "f" + bucket(a.forward())
                + "s" + bucket(a.strafe())
                + "j" + a.jump()
                + "sp" + a.sprint()
                + "yd" + bucket(a.yawDelta())
                + "ab" + a.useAbility();
    }

    private static int bucket(double v) {
        if (v < -0.75) return -1;
        if (v < -0.25) return -2;
        if (v < 0.25) return 0;
        if (v < 0.75) return 1;
        return 2;
    }

    private static int bucket(float v) {
        if (v < -20) return -2;
        if (v < -1) return -1;
        if (v <= 1) return 0;
        if (v <= 20) return 1;
        return 2;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
