package me.monstermazeai.competitor;

import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.PlayerBehaviorProfile;

/** Immutable configuration for one distinct Monster Maze AI competitor. */
public final class CompetitorDefinition {
    public final String id;
    public final String displayName;
    public final Kit kit;
    public final PlayerBehaviorProfile profile;
    public final boolean allowJump;

    public CompetitorDefinition(String id, String displayName, Kit kit,
                                PlayerBehaviorProfile profile, boolean allowJump) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("id");
        if (displayName == null || displayName.trim().isEmpty()) throw new IllegalArgumentException("displayName");
        if (kit == null || profile == null) throw new IllegalArgumentException("kit/profile");
        this.id=id; this.displayName=displayName; this.kit=kit;
        this.profile=profile; this.allowJump=allowJump;
    }
}
