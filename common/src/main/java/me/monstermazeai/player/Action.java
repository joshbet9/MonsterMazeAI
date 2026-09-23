package me.monstermazeai.player;

public record Action(double forward, double strafe, boolean jump, boolean sprint, float yawDelta, boolean useAbility) {
    public static final Action IDLE = new Action(0, 0, false, false, 0, false);
}