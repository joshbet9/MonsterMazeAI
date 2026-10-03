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
    private static final int DEFAULT_MAX_TRAINING_TICKS = 480;

    @Test
    void collectShortPolicyRollouts() {
        int requestedCases = Integer.getInteger(
                "monstermaze.ml.policy.trainingCasesPerMode", 2);
        int cases = Math.max(1, Math.min(CASES_PER_MODE_LIMIT, requestedCases));
        long seedOffset = Long.getLong("monstermaze.sim.seedOffset", 0L);
        int maxTrainingTicks = Math.max(
                60,
                Integer.getInteger(
                        "monstermaze.ml.policy.trainingMaxTicks",
                        DEFAULT_MAX_TRAINING_TICKS));

        Kit[] kits = Kit.values();
        int totalCases = 3 * kits.length;
        int startCase = Math.floorMod(seedOffset, totalCases);

        for (int offset = 0; offset < cases; offset++) {
            int caseIndex = (startCase + offset) % totalCases;
            int pattern = caseIndex / kits.length;
            Kit kit = kits[caseIndex % kits.length];

            System.out.printf("POLICY_TRAIN_CASE %d/%d pattern=%d kit=%s%n",
                    offset + 1, cases, pattern + 1, kit);
            runCase(pattern, kit, Mode.SPEED, maxTrainingTicks);
            runCase(pattern, kit, Mode.MODERN, maxTrainingTicks);
        }
    }

    private static void runCase(int pattern, Kit kit, Mode mode, int maxTrainingTicks) {
        System.out.printf("POLICY_TRAIN_START mode=%s pattern=%d kit=%s maxTicks=%d%n",
                mode, pattern + 1, kit, maxTrainingTicks);
        AuthenticStage10SimulationTest.RunResult result =
                AuthenticStage10SimulationTest.runForTicks(
                        pattern, kit, AiProfile.HIGH_SKILL, mode, maxTrainingTicks);

        System.out.printf(
                "POLICY_TRAIN_RUN mode=%s pattern=%d kit=%s stage=%d ticks=%d seedOffset=%d%n",
                mode, pattern + 1, kit, result.maxStage(), result.ticks(),
                Long.getLong("monstermaze.sim.seedOffset", 0L));
    }
}
