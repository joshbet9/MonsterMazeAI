package me.monstermazeai.player;

/**
 * Complete autonomous-player personality: physical capability attributes plus
 * behavioural tendencies. Source game mechanics remain outside the profile.
 */
public final class AiProfile {
    public static final AiProfile BASELINE =
            new AiProfile(AiAttributes.BASELINE, AiTendencies.BASELINE);

    public final AiAttributes attributes;
    public final AiTendencies tendencies;

    public AiProfile(AiAttributes attributes, AiTendencies tendencies) {
        if (attributes == null) throw new IllegalArgumentException("attributes");
        if (tendencies == null) throw new IllegalArgumentException("tendencies");
        this.attributes = attributes;
        this.tendencies = tendencies;
    }
}
