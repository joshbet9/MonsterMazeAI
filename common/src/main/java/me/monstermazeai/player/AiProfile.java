package me.monstermazeai.player;

/**
 * Complete autonomous-player personality: physical capability attributes plus
 * behavioural tendencies. Source game mechanics remain outside the profile.
 */
public final class AiProfile {
    public static final AiProfile BASELINE =
            new AiProfile(AiAttributes.BASELINE, AiTendencies.BASELINE);

    /** Initial high-skill candidate; subsequent tuning is evidence-driven. */
    public static final AiProfile HIGH_SKILL =
            new AiProfile(
                    new AiAttributes(1.0, 0.85, 0.85, 0.90),
                    new AiTendencies(
                            AiTendencies.DirectionChangeType.MIXED,
                            0.70, 0.55, 0.90, 0.85, 0.80, 0.80));

    public final AiAttributes attributes;
    public final AiTendencies tendencies;

    public AiProfile(AiAttributes attributes, AiTendencies tendencies) {
        if (attributes == null) throw new IllegalArgumentException("attributes");
        if (tendencies == null) throw new IllegalArgumentException("tendencies");
        this.attributes = attributes;
        this.tendencies = tendencies;
    }
}
