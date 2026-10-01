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
 * The learned policy is deliberately residual: it can perturb the trusted
 * controller, but it cannot jump to an unrelated idle/reverse/cardinal action
 * until that action space is supported by substantially more training data.
 * Exploration uses the same bounded family so replay remains mechanically
 * executable and close to the baseline distribution.
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
    private static final double SWITCH_MARGIN = 3.0;
    private static final double DEVIATION_PENALTY = 4.0;

    private PolicyActionSelector() {}

    public static Action select(GameState state, Action baseline, boolean allowJump) {
        if (state == null || baseline == null) return baseline;

        boolean policyMode = "policy".equalsIgnoreCase(
                System.getProperty("monstermaze.ml.mode", ""));
        boolean explore = Boolean.parseBoolean(
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

            double predicted = loaded.predict(state, candidate);
            double score = predicted - DEVIATION_PENALTY * deviation(candidate, baseline);

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

    private static List<Action> candidates(Action baseline, boolean allowJump, GameState state) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Action> out = new ArrayList<>(16);

        /*
         * Residual steering: keep the baseline movement intent while allowing
         * the learned layer to correct heading, lane, momentum and jump timing.
         */
        add(out, seen, baseline);
        add(out, seen, copy(baseline, baseline.forward(), baseline.strafe(),
                baseline.jump(), baseline.sprint(), -30, baseline.useAbility()));
        add(out, seen, copy(baseline, baseline.forward(), baseline.strafe(),
                baseline.jump(), baseline.sprint(), 30, baseline.useAbility()));

        add(out, seen, copy(baseline, baseline.forward(),
                clamp(baseline.strafe() - 0.35), baseline.jump(), baseline.sprint(),
                baseline.yawDelta(), baseline.useAbility()));
        add(out, seen, copy(baseline, baseline.forward(),
                clamp(baseline.strafe() + 0.35), baseline.jump(), baseline.sprint(),
                baseline.yawDelta(), baseline.useAbility()));

        add(out, seen, copy(baseline,
                clamp(baseline.forward() - 0.25), baseline.strafe(),
                baseline.jump(), baseline.sprint(), baseline.yawDelta(), baseline.useAbility()));
        add(out, seen, copy(baseline,
                clamp(baseline.forward() + 0.25), baseline.strafe(),
                baseline.jump(), baseline.sprint(), baseline.yawDelta(), baseline.useAbility()));

        if (allowJump) {
            add(out, seen, copy(baseline, baseline.forward(), baseline.strafe(),
                    true, baseline.sprint(), baseline.yawDelta(), baseline.useAbility()));
            add(out, seen, copy(baseline, baseline.forward(), baseline.strafe(),
                    false, baseline.sprint(), baseline.yawDelta(), baseline.useAbility()));
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

    private static double deviation(Action candidate, Action baseline) {
        double movement = Math.abs(candidate.forward() - baseline.forward())
                + Math.abs(candidate.strafe() - baseline.strafe());
        double jump = candidate.jump() == baseline.jump() ? 0.0 : 0.5;
        double sprint = candidate.sprint() == baseline.sprint() ? 0.0 : 0.25;
        double yaw = Math.abs(candidate.yawDelta() - baseline.yawDelta()) / 30.0;
        double ability = candidate.useAbility() == baseline.useAbility() ? 0.0 : 0.75;
        return movement + jump + sprint + yaw + ability;
    }

    private static double clamp(double value) {
        return Math.max(-1.0, Math.min(1.0, value));
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
