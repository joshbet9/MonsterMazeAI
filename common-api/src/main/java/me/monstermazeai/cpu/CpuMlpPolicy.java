package me.monstermazeai.cpu;

/**
 * Direct two-stage MLP policy implementation for the v2 runtime contract.
 *
 * <p>The tactical network runs only when the caller requests a new intent.
 * The locomotion network is a direct one-pass action policy and performs no
 * search or candidate simulation.
 */
public final class CpuMlpPolicy implements CpuPolicy {
    private static final int TARGET_OFFSET = 0;
    private static final int MONSTER_OFFSET = 5;
    private static final int ABILITY_OFFSET = 9;
    private static final int COMPETITOR_OFFSET = 12;
    private static final int RISK_OFFSET = 15;
    private static final int CONFIDENCE_OFFSET = 16;

    private final CpuMlpNetwork tacticalNetwork;
    private final CpuMlpNetwork locomotionNetwork;

    private final float[] profileVector = new float[CpuPolicySchema.PROFILE_COUNT];
    private final float[] tacticalInput = new float[CpuPolicySchema.TACTICAL_INPUT_COUNT];
    private final float[] tacticalOutput = new float[CpuPolicySchema.TACTICAL_VECTOR_COUNT];
    private final float[] locomotionInput = new float[CpuPolicySchema.LOCOMOTION_INPUT_COUNT];
    private final float[] locomotionOutput = new float[CpuPolicySchema.LOCOMOTION_OUTPUT_COUNT];

    public CpuMlpPolicy(CpuMlpNetwork tacticalNetwork, CpuMlpNetwork locomotionNetwork) {
        if (tacticalNetwork == null || locomotionNetwork == null) {
            throw new IllegalArgumentException("Both policy networks are required");
        }
        if (tacticalNetwork.inputCount() != CpuPolicySchema.TACTICAL_INPUT_COUNT
                || tacticalNetwork.outputCount() != CpuPolicySchema.TACTICAL_VECTOR_COUNT) {
            throw new IllegalArgumentException("Incompatible tactical network dimensions");
        }
        if (locomotionNetwork.inputCount() != CpuPolicySchema.LOCOMOTION_INPUT_COUNT
                || locomotionNetwork.outputCount() != CpuPolicySchema.LOCOMOTION_OUTPUT_COUNT) {
            throw new IllegalArgumentException("Incompatible locomotion network dimensions");
        }
        this.tacticalNetwork = tacticalNetwork;
        this.locomotionNetwork = locomotionNetwork;
    }

    @Override
    public void decideTactical(float[] observationFeatures, CpuProfile profile, CpuIntent outIntent) {
        CpuPolicyValidator.validateObservation(observationFeatures);
        CpuPolicyValidator.validateProfile(profile);
        CpuPolicyValidator.validateIntent(outIntent);

        profile.toVector(profileVector);
        System.arraycopy(observationFeatures, 0, tacticalInput, 0, CpuPolicySchema.FEATURE_COUNT);
        System.arraycopy(profileVector, 0, tacticalInput,
                CpuPolicySchema.FEATURE_COUNT, CpuPolicySchema.PROFILE_COUNT);

        tacticalNetwork.forward(tacticalInput, tacticalOutput);

        outIntent.targetMode = argmax(tacticalOutput, TARGET_OFFSET, 5);
        outIntent.monsterMode = argmax(tacticalOutput, MONSTER_OFFSET, 4);
        outIntent.abilityMode = argmax(tacticalOutput, ABILITY_OFFSET, 3);
        outIntent.competitorMode = argmax(tacticalOutput, COMPETITOR_OFFSET, 3);
        outIntent.risk = sigmoid(tacticalOutput[RISK_OFFSET]);
        outIntent.confidence = sigmoid(tacticalOutput[CONFIDENCE_OFFSET]);
    }

    @Override
    public void decideLocomotion(
            float[] observationFeatures,
            CpuProfile profile,
            CpuIntent intent,
            CpuAction outAction) {
        CpuPolicyValidator.validateObservation(observationFeatures);
        CpuPolicyValidator.validateProfile(profile);
        CpuPolicyValidator.validateIntent(intent);
        if (outAction == null) throw new IllegalArgumentException("action");

        profile.toVector(profileVector);
        System.arraycopy(observationFeatures, 0, locomotionInput, 0, CpuPolicySchema.FEATURE_COUNT);
        System.arraycopy(profileVector, 0, locomotionInput,
                CpuPolicySchema.FEATURE_COUNT, CpuPolicySchema.PROFILE_COUNT);
        encodeIntent(intent, locomotionInput, CpuPolicySchema.FEATURE_COUNT + CpuPolicySchema.PROFILE_COUNT);

        locomotionNetwork.forward(locomotionInput, locomotionOutput);

        double forward = Math.tanh(locomotionOutput[0]);
        double yaw = Math.tanh(locomotionOutput[1]) * 30.0;
        double strafe = Math.tanh(locomotionOutput[2]);
        boolean jump = sigmoid(locomotionOutput[3]) >= jumpThreshold(profile.jumpPreference);
        boolean sprint = sigmoid(locomotionOutput[4]) >= sprintThreshold(profile.speedSkill);

        double primary = sigmoid(locomotionOutput[5]);
        double enhanced = sigmoid(locomotionOutput[6]);
        boolean useAbility = abilityThreshold(primary, enhanced, intent.abilityMode);

        outAction.set(forward, strafe, (float) yaw, jump, sprint, useAbility);
    }

    @Override
    public void reset(long seed) {
        // The base MLP is deterministic. Seeded variance is applied by a future
        // policy wrapper so this core runtime stays stateless and allocation-free.
    }

    private static void encodeIntent(
            CpuIntent intent, float[] destination, int offset) {
        int i = offset;
        oneHot(destination, i, 5, intent.targetMode); i += 5;
        oneHot(destination, i, 4, intent.monsterMode); i += 4;
        oneHot(destination, i, 3, intent.abilityMode); i += 3;
        oneHot(destination, i, 3, intent.competitorMode); i += 3;
        destination[i++] = clamp01(intent.risk);
        destination[i] = clamp01(intent.confidence);
    }

    private static void oneHot(float[] destination, int offset, int count, int selected) {
        for (int i = 0; i < count; i++) destination[offset + i] = i == selected ? 1.0f : 0.0f;
    }

    private static int argmax(float[] values, int offset, int count) {
        int best = 0;
        float bestValue = values[offset];
        for (int i = 1; i < count; i++) {
            float value = values[offset + i];
            if (value > bestValue) {
                bestValue = value;
                best = i;
            }
        }
        return best;
    }

    private static boolean abilityThreshold(double primary, double enhanced, int abilityMode) {
        double threshold = abilityMode == 0 ? 0.80 : abilityMode == 1 ? 0.55 : 0.35;
        return abilityMode == 2 ? enhanced >= threshold : primary >= threshold;
    }

    private static double jumpThreshold(float preference) {
        return 0.50 - 0.20 * clamp01(preference);
    }

    private static double sprintThreshold(float speedSkill) {
        return 0.65 - 0.20 * clamp01(speedSkill);
    }

    private static float sigmoid(float value) {
        if (value >= 18.0f) return 1.0f;
        if (value <= -18.0f) return 0.0f;
        return (float) (1.0 / (1.0 + Math.exp(-value)));
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
