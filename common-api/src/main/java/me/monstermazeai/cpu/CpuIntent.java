package me.monstermazeai.cpu;

/**
 * Compact tactical intent emitted by the slower CPU decision layer.
 *
 * <p>The values are semantic. They are not Minecraft inputs.
 */
public final class CpuIntent {
    public static final int TARGET_CURRENT_PAD = 0;
    public static final int TARGET_FAST_ROUTE = 1;
    public static final int TARGET_SAFE_ROUTE = 2;
    public static final int TARGET_RECOVERY = 3;
    public static final int TARGET_CONTEST = 4;

    public static final int MONSTERS_AVOID = 0;
    public static final int MONSTERS_TOLERATE = 1;
    public static final int MONSTERS_EXPLOIT = 2;
    public static final int MONSTERS_REPULSE = 3;

    public static final int ABILITY_SAVE = 0;
    public static final int ABILITY_NORMAL = 1;
    public static final int ABILITY_URGENT = 2;

    public static final int COMPETE_IGNORE = 0;
    public static final int COMPETE_RACE = 1;
    public static final int COMPETE_OVERTAKE = 2;

    public int targetMode = TARGET_CURRENT_PAD;
    public int monsterMode = MONSTERS_AVOID;
    public int abilityMode = ABILITY_SAVE;
    public int competitorMode = COMPETE_IGNORE;
    public float risk = 0.5f;
    public float confidence = 0.0f;

    public CpuIntent reset() {
        targetMode = TARGET_CURRENT_PAD;
        monsterMode = MONSTERS_AVOID;
        abilityMode = ABILITY_SAVE;
        competitorMode = COMPETE_IGNORE;
        risk = 0.5f;
        confidence = 0.0f;
        return this;
    }
}
