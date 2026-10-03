package me.monstermazeai.cpu;

/**
 * Reusable semantic CPU action buffer.
 *
 * <p>These are intents passed to the authoritative game executor. They never
 * directly modify player state.
 */
public final class CpuAction {
    public double forward;
    public double strafe;
    public float yawDelta;
    public boolean jump;
    public boolean sprint;
    public boolean useAbility;

    public CpuAction() {
        reset();
    }

    public CpuAction reset() {
        forward = 0.0;
        strafe = 0.0;
        yawDelta = 0.0f;
        jump = false;
        sprint = false;
        useAbility = false;
        return this;
    }

    public CpuAction set(double forward, double strafe, float yawDelta,
                         boolean jump, boolean sprint, boolean useAbility) {
        this.forward = clamp(forward);
        this.strafe = clamp(strafe);
        this.yawDelta = Math.max(-30.0f, Math.min(30.0f, yawDelta));
        this.jump = jump;
        this.sprint = sprint;
        this.useAbility = useAbility;
        return this;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0;
        return Math.max(-1.0, Math.min(1.0, value));
    }
}
