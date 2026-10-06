package me.monstermazeai.player;

public record Action(double forward, double strafe, boolean jump, boolean sprint,
                     float yawDelta, boolean useAbility) {
    public static final Action IDLE = new Action(0, 0, false, false, 0, false);

    /** Minecraft 1.8.9's client bridge can turn at most 30 degrees per tick. */
    public Action {
        if (Double.isNaN(forward) || Double.isInfinite(forward)) forward = 0.0;
        if (Double.isNaN(strafe) || Double.isInfinite(strafe)) strafe = 0.0;
        forward = Math.max(-1.0, Math.min(1.0, forward));
        strafe = Math.max(-1.0, Math.min(1.0, strafe));

        if (Float.isNaN(yawDelta) || Float.isInfinite(yawDelta)) yawDelta = 0.0f;
        yawDelta = Math.max(-30.0f, Math.min(30.0f, yawDelta));

        /*
         * This is a common-core control contract, not a live-only adapter rule.
         * Vanilla 1.8.9 cancels sprint below 0.8 forward input, so an Action that
         * claims to sprint with a lower forward value would make the simulator
         * and the real client execute different physics.
         */
        sprint = sprint && forward >= 0.8D;
    }

    /** Cardinal direction while holding the current facing. */
    public static Action forward(boolean jump) {
        return new Action(1, 0, jump, true, 0, false);
    }

    /** Rotate the player's facing without changing movement input. */
    public static Action turn(float yawDelta) {
        return new Action(0, 0, false, false, yawDelta, false);
    }

    /**
     * Apply the requested yaw change before the physics step. This keeps
     * turning an explicit control dimension so route planning can choose a
     * heading rather than being forced to remain on the initial yaw.
     */
    public Action withYawDelta(float delta) {
        return new Action(forward, strafe, jump, sprint, delta, useAbility);
    }
}
