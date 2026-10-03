package me.monstermazeai.cpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CpuMlpPolicyTest {

    @Test
    void mapsTacticalHeadIntoSemanticIntentAndDirectAction() {
        float[] tacticalBias = new float[CpuPolicySchema.TACTICAL_VECTOR_COUNT];
        tacticalBias[2] = 3.0f;   // TARGET_SAFE_ROUTE
        tacticalBias[8] = 3.0f;   // MONSTERS_REPULSE
        tacticalBias[11] = 3.0f;  // ABILITY_URGENT
        tacticalBias[14] = 3.0f;  // COMPETE_OVERTAKE
        tacticalBias[15] = 0.0f;  // risk = 0.5
        tacticalBias[16] = 2.0f;  // confidence > 0.5

        float[] locomotionBias = new float[CpuPolicySchema.LOCOMOTION_OUTPUT_COUNT];
        locomotionBias[0] = 1.0f;
        locomotionBias[1] = -0.5f;
        locomotionBias[2] = 0.25f;
        locomotionBias[3] = 2.0f;
        locomotionBias[4] = 2.0f;
        locomotionBias[5] = 2.0f;
        locomotionBias[6] = 2.0f;

        CpuMlpNetwork tactical = zeroHidden(
                CpuPolicySchema.TACTICAL_INPUT_COUNT,
                CpuPolicySchema.TACTICAL_VECTOR_COUNT,
                tacticalBias);
        CpuMlpNetwork locomotion = zeroHidden(
                CpuPolicySchema.LOCOMOTION_INPUT_COUNT,
                CpuPolicySchema.LOCOMOTION_OUTPUT_COUNT,
                locomotionBias);

        CpuMlpPolicy policy = new CpuMlpPolicy(tactical, locomotion);
        CpuProfile profile = CpuProfile.baseline();
        float[] observation = new float[CpuPolicySchema.FEATURE_COUNT];
        CpuIntent intent = new CpuIntent();
        CpuAction action = new CpuAction();

        policy.decideTactical(observation, profile, intent);
        assertEquals(CpuIntent.TARGET_SAFE_ROUTE, intent.targetMode);
        assertEquals(CpuIntent.MONSTERS_REPULSE, intent.monsterMode);
        assertEquals(CpuIntent.ABILITY_URGENT, intent.abilityMode);
        assertEquals(CpuIntent.COMPETE_OVERTAKE, intent.competitorMode);
        assertEquals(0.5f, intent.risk, 1e-5f);
        assertTrue(intent.confidence > 0.8f);

        policy.decideLocomotion(observation, profile, intent, action);
        assertTrue(action.forward > 0.7);
        assertTrue(action.strafe > 0.2);
        assertTrue(action.yawDelta < 0.0f);
        assertTrue(action.yawDelta > -30.0f);
        assertTrue(action.jump);
        assertTrue(action.sprint);
        assertTrue(action.useAbility);
    }

    @Test
    void mlpRejectsIncompatibleBuffers() {
        CpuMlpNetwork network = zeroHidden(3, 2, new float[] {0.0f, 0.0f});
        assertThrows(IllegalArgumentException.class,
                () -> network.forward(new float[2], new float[2]));
        assertThrows(IllegalArgumentException.class,
                () -> network.forward(new float[3], new float[1]));
    }

    private static CpuMlpNetwork zeroHidden(int inputCount, int outputCount, float[] outputBias) {
        return new CpuMlpNetwork(
                inputCount,
                1,
                outputCount,
                new float[inputCount],
                new float[] {0.0f},
                new float[outputCount],
                outputBias);
    }
}
