package me.monstermazeai.cpu;

/**
 * Immutable CPU-player configuration.
 *
 * <p>Values are normalized to [0,1]. They describe capability and behaviour;
 * they never modify Monster Maze's actual physics or mechanics.
 */
public final class CpuProfile {
    public final float difficulty;

    // Capability attributes.
    public final float speedSkill;
    public final float agility;
    public final float handling;
    public final float reactions;
    public final float planning;
    public final float recovery;
    public final float abilitySkill;

    // Behavioural tendencies.
    public final float riskTolerance;
    public final float aggression;
    public final float monsterContactPreference;
    public final float abilityConservation;
    public final float jumpPreference;
    public final float routePreference;
    public final float padContestPreference;
    public final float strafePreference;
    public final float actionVariance;

    public CpuProfile(
            float difficulty,
            float speedSkill,
            float agility,
            float handling,
            float reactions,
            float planning,
            float recovery,
            float abilitySkill,
            float riskTolerance,
            float aggression,
            float monsterContactPreference,
            float abilityConservation,
            float jumpPreference,
            float routePreference,
            float padContestPreference,
            float strafePreference,
            float actionVariance) {
        this.difficulty = bounded("difficulty", difficulty);
        this.speedSkill = bounded("speedSkill", speedSkill);
        this.agility = bounded("agility", agility);
        this.handling = bounded("handling", handling);
        this.reactions = bounded("reactions", reactions);
        this.planning = bounded("planning", planning);
        this.recovery = bounded("recovery", recovery);
        this.abilitySkill = bounded("abilitySkill", abilitySkill);
        this.riskTolerance = bounded("riskTolerance", riskTolerance);
        this.aggression = bounded("aggression", aggression);
        this.monsterContactPreference = bounded("monsterContactPreference", monsterContactPreference);
        this.abilityConservation = bounded("abilityConservation", abilityConservation);
        this.jumpPreference = bounded("jumpPreference", jumpPreference);
        this.routePreference = bounded("routePreference", routePreference);
        this.padContestPreference = bounded("padContestPreference", padContestPreference);
        this.strafePreference = bounded("strafePreference", strafePreference);
        this.actionVariance = bounded("actionVariance", actionVariance);
    }

    public static CpuProfile baseline() {
        return new CpuProfile(
                0.50f,
                0.60f, 0.50f, 0.50f, 0.50f, 0.50f, 0.50f, 0.50f,
                0.35f, 0.50f, 0.25f, 0.50f, 0.50f, 0.50f, 0.50f, 0.25f, 0.00f);
    }

    /**
     * Returns the fixed-order profile vector used by model inference.
     */
    public float[] toVector(float[] reuse) {
        float[] out = reuse != null && reuse.length == CpuPolicySchema.PROFILE_COUNT
                ? reuse : new float[CpuPolicySchema.PROFILE_COUNT];
        out[0] = difficulty;
        out[1] = speedSkill;
        out[2] = agility;
        out[3] = handling;
        out[4] = reactions;
        out[5] = planning;
        out[6] = recovery;
        out[7] = abilitySkill;
        out[8] = riskTolerance;
        out[9] = aggression;
        out[10] = monsterContactPreference;
        out[11] = abilityConservation;
        out[12] = jumpPreference;
        out[13] = routePreference;
        out[14] = padContestPreference;
        out[15] = strafePreference;
        out[16] = actionVariance;
        return out;
    }

    private static float bounded(String name, float value) {
        if (Float.isNaN(value) || Float.isInfinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(name + " must be finite and in [0,1]");
        }
        return value;
    }
}
