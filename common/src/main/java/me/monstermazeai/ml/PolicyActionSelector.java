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
 * The learned policy evaluates a bounded but expressive action space around
 * the trusted controller. The action space is shared with counterfactual
 * training, so every deployable alternative is first measured by the real
 * source-faithful simulator.
 */
public final class PolicyActionSelector {
    private static final Object LOCK = new Object();
    private static PolicyActionModel model;
    private static boolean initialised;
    private static final Random RANDOM = new Random(0x4D4D504FL);

    /*
     * Conservative exploitation guard. A learned action must beat the baseline
     * prediction by a real margin after a small regularisation penalty for
     * changing the trusted controller's command.
     */
    private static final double SWITCH_MARGIN = 0.5;

    private PolicyActionSelector() {}

    public static Action select(GameState state, Action baseline, boolean allowJump) {
        if (state == null || baseline == null) return baseline;

        boolean policyMode = "policy".equalsIgnoreCase(
                System.getProperty("monstermaze.ml.mode", ""));
        boolean counterfactual = Boolean.parseBoolean(
                System.getProperty("monstermaze.ml.policy.counterfactual", "false"));
        boolean explore = !counterfactual && Boolean.parseBoolean(
                System.getProperty("monstermaze.ml.policy.explore", "false"));

        PolicyActionModel loaded = model();

        /*
         * Exploration is allowed to try the same residual actions the policy
         * can later choose. This avoids collecting mostly unsupported actions
         * that exploitation is then tempted to extrapolate toward.
         */
        List<Action> candidates = candidates(baseline, allowJump, state);

        if (explore) {
            double epsilon = clamp01(Double.parseDouble(
                    System.getProperty("monstermaze.ml.policy.epsilon", "0.20")));
            if (RANDOM.nextDouble() < epsilon) {
                return candidates.get(RANDOM.nextInt(candidates.size()));
            }
            if (loaded == null) return baseline;
        }

        if (!policyMode || loaded == null) return baseline;

        double baselineValue = loaded.predict(state, baseline);
        Action best = baseline;
        double bestScore = baselineValue;

        for (Action candidate : candidates) {
            if (candidate.equals(baseline)) continue;

            double score = loaded.predict(state, candidate);

            if (score > bestScore + SWITCH_MARGIN) {
                bestScore = score;
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

    static List<Action> candidates(Action baseline, boolean allowJump, GameState state) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Action> out = new ArrayList<>(24);

        add(out, seen, baseline);

        // Heading corrections.
        add(out, seen, copy(baseline, baseline.forward(), baseline.strafe(),
                baseline.jump(), baseline.sprint(), -30, baseline.useAbility()));
        add(out, seen, copy(baseline, baseline.forward(), baseline.strafe(),
                baseline.jump(), baseline.sprint(), 30, baseline.useAbility()));

        // Lane / momentum corrections.
        for (double delta : new double[]{-0.75, -0.35, 0.35, 0.75}) {
            add(out, seen, copy(baseline, baseline.forward(),
                    clamp(baseline.strafe() + delta), baseline.jump(), baseline.sprint(),
                    baseline.yawDelta(), baseline.useAbility()));
        }
        for (double delta : new double[]{-0.50, -0.25, 0.25, 0.50}) {
            add(out, seen, copy(baseline, clamp(baseline.forward() + delta),
                    baseline.strafe(), baseline.jump(), baseline.sprint(),
                    baseline.yawDelta(), baseline.useAbility()));
        }

        // Explicit alternatives allow the learned policy to discover a
        // different local lane rather than merely perturbing the baseline.
        for (double strafe : new double[]{-1.0, -0.75, 0.75, 1.0}) {
            add(out, seen, copy(baseline, 1.0, strafe, baseline.jump(),
                    baseline.sprint(), baseline.yawDelta(), baseline.useAbility()));
        }
        add(out, seen, copy(baseline, 1.0, 0.0, baseline.jump(),
                baseline.sprint(), baseline.yawDelta(), baseline.useAbility()));
        add(out, seen, copy(baseline, 0.0, 0.75, baseline.jump(),
                baseline.sprint(), baseline.yawDelta(), baseline.useAbility()));
        add(out, seen, copy(baseline, 0.0, -0.75, baseline.jump(),
                baseline.sprint(), baseline.yawDelta(), baseline.useAbility()));
        add(out, seen, copy(baseline, -0.5, 0.0, baseline.jump(),
                baseline.sprint(), baseline.yawDelta(), baseline.useAbility()));
        add(out, seen, copy(baseline, baseline.forward(), baseline.strafe(),
                baseline.jump(), false, baseline.yawDelta(), baseline.useAbility()));

        if (allowJump) {
            add(out, seen, copy(baseline, baseline.forward(), baseline.strafe(),
                    true, baseline.sprint(), baseline.yawDelta(), baseline.useAbility()));
            add(out, seen, copy(baseline, 1.0, 0.75, true,
                    baseline.sprint(), baseline.yawDelta(), baseline.useAbility()));
            add(out, seen, copy(baseline, 1.0, -0.75, true,
                    baseline.sprint(), baseline.yawDelta(), baseline.useAbility()));
        }

        boolean canUseAbility = state.ability != null
                && ((state.kit == Kit.BODY_BUILDER && state.ability.activations > 0
                        && state.ability.activeUntilTick <= state.tick)
                    || (state.kit != Kit.BODY_BUILDER
                        && state.kit != Kit.JUMPER
                        && state.ability.charges > 0
                        && state.tick >= state.ability.cooldownUntilTick));
        if (canUseAbility) {
            add(out, seen, copy(baseline, baseline.forward(), baseline.strafe(),
                    baseline.jump(), baseline.sprint(), baseline.yawDelta(), true));
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

    private static double clamp(double value) {
        return Math.max(-1.0, Math.min(1.0, value));
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
