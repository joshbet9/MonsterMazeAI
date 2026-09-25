package me.monstermazeai.adapter;

/**
 * Java-8-compatible control command emitted by the AI boundary.
 * Values are normalized to [-1, 1] except yawDelta, which is degrees.
 */
public final class LegacyAction {
    public final double forward;
    public final double strafe;
    public final boolean jump;
    public final boolean sprint;
    public final float yawDelta;
    public final boolean useAbility;

    public LegacyAction(double forward, double strafe, boolean jump,
                        boolean sprint, float yawDelta, boolean useAbility) {
        this.forward = clamp(forward);
        this.strafe = clamp(strafe);
        this.jump = jump;
        this.sprint = sprint;
        this.yawDelta = yawDelta;
        this.useAbility = useAbility;
    }

    public static final LegacyAction IDLE =
            new LegacyAction(0.0, 0.0, false, false, 0.0f, false);

    private static double clamp(double value) {
        return Math.max(-1.0, Math.min(1.0, value));
    }
}
