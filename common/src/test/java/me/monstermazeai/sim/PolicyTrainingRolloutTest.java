package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import org.junit.jupiter.api.Test;

/**
 * Short, representative simulator rollouts used only to collect policy-learning
 * counterfactual data. Full 30-case matrices remain reserved for promotion gates.
 *
 * The case window rotates from the configured seed offset so successive ML cycles
 * do not repeatedly train on the same pattern/kit pairs.
 */
class PolicyTrainingRolloutTest {
    private static final int CASES_PER_MODE_LIMIT = 15;

    @Test
    void collectShortPolicyRollouts() {
        int requestedCases = Integer.getInteger(
                "monstermaze.ml.policy.trainingCasesPerMode", 2);
        int cases = Math.max(1, Math.min(CASES_PER_MODE_LIMIT, requestedCases));
        long seedOffset = Long.getLong("monstermaze.sim.seedOffset", 0L);

        Kit[] kits = Kit.values();
        int totalCases = 3 * kits.length;
        int startCase = Math.floorMod(seedOffset, totalCases);

        for (int offset = 0; offset < cases; offset++) {
            int caseIndex = (startCase + offset) % totalCases;
            int pattern = caseIndex / kits.length;
            Kit kit = kits[caseIndex % kits.length];

            runCase(pattern, kit, Mode.SPEED, 10);
            runCase(pattern, kit, Mode.MODERN, 5);
        }
    }

    private static void runCase(int pattern, Kit kit, Mode mode, int targetStage) {
        AuthenticStage10SimulationTest.RunResult result =
                AuthenticStage10SimulationTest.runDiagnostic(
                        pattern, kit, AiProfile.HIGH_SKILL, mode, targetStage);

        System.out.printf(
                "POLICY_TRAIN_RUN mode=%s pattern=%d kit=%s stage=%d ticks=%d seedOffset=%d%n",
                mode, pattern + 1, kit, result.maxStage(), result.ticks(),
                Long.getLong("monstermaze.sim.seedOffset", 0L));
    }
}
