package me.monstermazeai.player;

public record Action(double forward, double strafe, boolean jump, boolean sprint,
                     float yawDelta, boolean useAbility) {
    public static final Action IDLE = new Action(0, 0, false, false, 0, false);

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
