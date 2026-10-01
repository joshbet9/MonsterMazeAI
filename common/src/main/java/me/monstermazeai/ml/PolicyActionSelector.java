package me.monstermazeai.ml;

import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.Action;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

/**
 * Optional policy layer above the deterministic movement controller.
 *
 * In policy mode the learned model chooses among a bounded set of executable
 * control alternatives. In explore mode epsilon-random alternatives provide
 * counterfactual data for long-horizon self-training.
 */
public final class PolicyActionSelector {
    private static final Object LOCK = new Object();
    private static PolicyActionModel model;
    private static boolean initialised;
    private static final Random RANDOM = new Random(0x4D4D504FL);

    private PolicyActionSelector() {}

    public static Action select(GameState state, Action baseline, boolean allowJump) {
        if (state == null || baseline == null) return baseline;

        boolean policyMode = "policy".equalsIgnoreCase(
                System.getProperty("monstermaze.ml.mode", ""));
        boolean explore = Boolean.parseBoolean(
                System.getProperty("monstermaze.ml.policy.explore", "false"));

        PolicyActionModel loaded = model();
        List<Action> candidates = candidates(baseline, allowJump, state);

        if (explore) {
            double epsilon = clamp01(Double.parseDouble(
                    System.getProperty("monstermaze.ml.policy.epsilon", "0.20")));
            if (RANDOM.nextDouble() < epsilon || loaded == null) {
                return candidates.get(RANDOM.nextInt(candidates.size()));
            }
        }

        if (!policyMode || loaded == null) return baseline;

        Action best = baseline;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (Action candidate : candidates) {
            double value = loaded.predict(state, candidate);
            if (value > bestValue) {
                bestValue = value;
                best = candidate;
            }
        }
        return best;
    }

    public static boolean loaded() {
        return model() != null;
    }

    private static PolicyActionModel model() {
        synchronized (LOCK) {
            if (initialised) return model;
            initialised = true;
            model = PolicyActionModel.loadFromProperty();
            return model;
        }
    }

    private static List<Action> candidates(Action baseline, boolean allowJump, GameState state) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Action> out = new ArrayList<>(32);

        add(out, seen, baseline);
        add(out, seen, copy(baseline, baseline.forward(), baseline.strafe(), baseline.jump(), baseline.sprint(), -30, baseline.useAbility()));
        add(out, seen, copy(baseline, baseline.forward(), baseline.strafe(), baseline.jump(), baseline.sprint(), 30, baseline.useAbility()));

        add(out, seen, copy(baseline, 1.0, 0.0, false, true, 0, false));
        add(out, seen, copy(baseline, 1.0, -1.0, false, true, 0, false));
        add(out, seen, copy(baseline, 1.0, 1.0, false, true, 0, false));
        add(out, seen, copy(baseline, 0.0, -1.0, false, false, 0, false));
        add(out, seen, copy(baseline, 0.0, 1.0, false, false, 0, false));
        add(out, seen, copy(baseline, -1.0, 0.0, false, true, 0, false));
        add(out, seen, copy(baseline, 0.0, 0.0, false, false, 0, false));

        if (allowJump) {
            add(out, seen, copy(baseline, 1.0, 0.0, true, true, 0, false));
            add(out, seen, copy(baseline, 1.0, -1.0, true, true, 0, false));
            add(out, seen, copy(baseline, 1.0, 1.0, true, true, 0, false));
            add(out, seen, copy(baseline, baseline.forward(), baseline.strafe(), true, baseline.sprint(), baseline.yawDelta(), baseline.useAbility()));
        }

        boolean canUseAbility = state.ability != null
                && (state.ability.charges > 0 || state.kit == Kit.BODY_BUILDER);
        if (canUseAbility) {
            add(out, seen, copy(baseline, baseline.forward(), baseline.strafe(), baseline.jump(), baseline.sprint(),
                    baseline.yawDelta(), true));
            add(out, seen, copy(baseline, 1.0, 0.0, allowJump, true, 0, true));
        }

        return out;
    }

    private static Action copy(Action base, double forward, double strafe, boolean jump,
                               boolean sprint, float yaw, boolean ability) {
        return new Action(forward, strafe, jump, sprint, yaw, ability);
    }

    private static void add(List<Action> out, LinkedHashSet<String> seen, Action action) {
        String key = action.forward() + "|" + action.strafe() + "|"
                + action.jump() + "|" + action.sprint() + "|"
                + action.yawDelta() + "|" + action.useAbility();
        if (seen.add(key)) out.add(action);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
