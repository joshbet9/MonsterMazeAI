package me.monstermazeai.player;

import me.monstermazeai.cpu.CpuProfile;
import me.monstermazeai.kit.Kit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CpuProfileAdapterTest {

    @Test
    void preservesLegacyCapabilityAndKitPreference() {
        AiProfile source = new AiProfile(
                new AiAttributes(0.9, 0.8, 0.7, 0.6),
                new AiTendencies(
                        AiTendencies.DirectionChangeType.STRAFE,
                        0.4, 0.3, 0.9, 0.2, 0.5, 0.6));

        CpuProfile profile = CpuProfileAdapter.from(source, Kit.JUMPER, 0.75f);

        assertEquals(0.75f, profile.difficulty);
        assertEquals(0.9f, profile.speedSkill);
        assertEquals(0.8f, profile.agility);
        assertEquals(0.7f, profile.handling);
        assertEquals(0.6f, profile.reactions);
        assertEquals(0.9f, profile.jumpPreference);
        assertEquals(0.9f, profile.abilitySkill);
        assertEquals(1.0f, profile.strafePreference);
    }

    @Test
    void selectsAbilityPreferenceForEquippedKit() {
        AiProfile source = new AiProfile(
                AiAttributes.BASELINE,
                new AiTendencies(
                        AiTendencies.DirectionChangeType.MIXED,
                        0.4, 0.2, 0.3, 0.9, 0.7, 0.6));

        CpuProfile profile = CpuProfileAdapter.from(source, Kit.REPULSOR, 0.5f);

        assertEquals(0.9f, profile.abilitySkill);
    }
}
