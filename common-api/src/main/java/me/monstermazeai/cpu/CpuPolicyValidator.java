package me.monstermazeai.cpu;

/**
 * Runtime validation helpers. Kept dependency-free so the production server
 * can fail closed before executing an incompatible policy.
 */
public final class CpuPolicyValidator {
    private CpuPolicyValidator() {}

    public static void validateObservation(float[] features) {
        if (features == null || features.length != CpuPolicySchema.FEATURE_COUNT) {
            throw new IllegalArgumentException(
                    "Expected " + CpuPolicySchema.FEATURE_COUNT + " observation features");
        }
    }

    public static void validateProfile(CpuProfile profile) {
        if (profile == null) throw new IllegalArgumentException("profile");
    }

    public static void validateIntent(CpuIntent intent) {
        if (intent == null) throw new IllegalArgumentException("intent");
    }

    public static void validateAction(CpuAction action) {
        if (action == null) throw new IllegalArgumentException("action");
        if (!Double.isFinite(action.forward) || !Double.isFinite(action.strafe)
                || !Float.isFinite(action.yawDelta)) {
            throw new IllegalArgumentException("Non-finite CPU action");
        }
    }
}
