package me.monstermazeai.player;

import me.monstermazeai.cpu.CpuProfile;
import me.monstermazeai.kit.Kit;

/**
 * Migration adapter from the existing MonsterMazeAI profile model to the
 * production CPU profile contract.
 *
 * <p>This preserves the existing attribute/tendency work while leaving newly
 * introduced profile dimensions at neutral defaults until training data
 * justifies different values.
 */
public final class CpuProfileAdapter {
    private CpuProfileAdapter() {}

    public static CpuProfile from(AiProfile source, Kit kit, float difficulty) {
        if (source == null) throw new IllegalArgumentException("source");
        if (kit == null) throw new IllegalArgumentException("kit");

        AiAttributes a = source.attributes;
        AiTendencies t = source.tendencies;

        float abilitySkill = (float) kitAbilityPreference(t, kit);
        float strafePreference;
        switch (t.directionChangeType) {
            case STRAFE:
                strafePreference = 1.0f;
                break;
            case MOUSE:
                strafePreference = 0.0f;
                break;
            default:
                strafePreference = 0.5f;
                break;
        }

        return new CpuProfile(
                difficulty,
                (float) a.maxSpeed,
                (float) a.agility,
                (float) a.handling,
                (float) a.reactions,
                0.50f,
                (float) a.handling,
                abilitySkill,
                (float) t.aggression,
                (float) t.aggression,
                (float) t.positiveMobKnockback,
                0.50f,
                (float) t.jumperIq,
                0.50f,
                (float) t.aggression,
                strafePreference,
                0.00f
        );
    }

    private static double kitAbilityPreference(AiTendencies t, Kit kit) {
        switch (kit) {
            case JUMPER:
                return t.jumperIq;
            case REPULSOR:
                return t.repulsorIq;
            case SLOWBALLER:
                return t.slowballerIq;
            case BODY_BUILDER:
                return t.bodyBuilderIq;
            default:
                return t.aggression;
        }
    }
}
