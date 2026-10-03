package me.monstermazeai.cpu;

/**
 * Stable production CPU-policy schema.
 *
 * <p>The schema is intentionally small and dependency-free so the same
 * contract can be used by MonsterMazeEngine and the Minecraft 1.8 server.
 */
public final class CpuPolicySchema {
    /** Runtime contract version. */
    public static final int VERSION = 2;

    /** Number of normalized observation features supplied to the policy. */
    public static final int FEATURE_COUNT = 96;

    /** Number of normalized profile values supplied to the policy. */
    public static final int PROFILE_COUNT = 17;

    /** Number of tactical intent values emitted by the tactical head. */
    public static final int INTENT_COUNT = 6;

    private CpuPolicySchema() {}
}
