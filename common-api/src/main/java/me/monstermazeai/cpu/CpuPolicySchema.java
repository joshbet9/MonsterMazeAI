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

    /** Legacy compact intent count retained for compatibility with earlier research code. */
    public static final int INTENT_COUNT = 6;

    /** Fixed semantic tactical vector: 5 target + 4 monster + 3 ability + 3 competitor + risk + confidence. */
    public static final int TACTICAL_VECTOR_COUNT = 17;

    /** Direct locomotion head: forward, steering, strafe, jump, sprint, primary ability, enhanced ability, confidence. */
    public static final int LOCOMOTION_OUTPUT_COUNT = 8;

    /** Tactical network input = observation + profile. */
    public static final int TACTICAL_INPUT_COUNT = FEATURE_COUNT + PROFILE_COUNT;

    /** Locomotion network input = observation + profile + semantic tactical vector. */
    public static final int LOCOMOTION_INPUT_COUNT = FEATURE_COUNT + PROFILE_COUNT + TACTICAL_VECTOR_COUNT;

    private CpuPolicySchema() {}
}
