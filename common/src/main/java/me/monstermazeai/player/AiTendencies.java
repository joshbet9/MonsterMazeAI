package me.monstermazeai.player;

/**
 * Normalized behavioural preferences for autonomous Monster Maze players.
 * Tendencies select between source-valid actions; they do not change physics.
 */
public final class AiTendencies {
    public enum DirectionChangeType {
        MIXED,
        STRAFE,
        MOUSE
    }

    public static final AiTendencies BASELINE = new AiTendencies(
            DirectionChangeType.MIXED,
            0.50, // aggression
            0.25, // positive mob knockback
            0.75, // Jumper IQ
            0.50, // Repulsor IQ
            0.50, // Slowballer IQ
            0.50  // Body Builder IQ
    );

    public final DirectionChangeType directionChangeType;
    public final double aggression;
    public final double positiveMobKnockback;
    public final double jumperIq;
    public final double repulsorIq;
    public final double slowballerIq;
    public final double bodyBuilderIq;

    public AiTendencies(DirectionChangeType directionChangeType,
                        double aggression,
                        double positiveMobKnockback,
                        double jumperIq,
                        double repulsorIq,
                        double slowballerIq,
                        double bodyBuilderIq) {
        if (directionChangeType == null) throw new IllegalArgumentException("directionChangeType");
        this.directionChangeType = directionChangeType;
        this.aggression = bounded("aggression", aggression);
        this.positiveMobKnockback = bounded("positiveMobKnockback", positiveMobKnockback);
        this.jumperIq = bounded("jumperIq", jumperIq);
        this.repulsorIq = bounded("repulsorIq", repulsorIq);
        this.slowballerIq = bounded("slowballerIq", slowballerIq);
        this.bodyBuilderIq = bounded("bodyBuilderIq", bodyBuilderIq);
    }

    private static double bounded(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be finite and in [0,1]");
        }
        return value;
    }
}
