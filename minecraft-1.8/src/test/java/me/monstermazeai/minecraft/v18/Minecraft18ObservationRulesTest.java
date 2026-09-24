package me.monstermazeai.minecraft.v18;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class Minecraft18ObservationRulesTest {
    @Test
    public void parsesInlineSafePadAndStage() {
        Minecraft18ObservationRules.ScoreboardData data =
                Minecraft18ObservationRules.parseScoreboard(
                        "\u00a7aMonster Maze",
                        Arrays.asList("Safe Pad: 17", "Stage 6"));

        assertEquals("Monster Maze", data.title);
        assertEquals(17, data.safePadSeconds);
        assertEquals(6, data.stage);
        assertTrue(Minecraft18ObservationRules.looksLikeMonsterMaze(data));
    }

    @Test
    public void parsesValuesOnFollowingScoreboardLines() {
        Minecraft18ObservationRules.ScoreboardData data =
                Minecraft18ObservationRules.parseScoreboard(
                        "Monster Maze",
                        Arrays.asList("Safe Pad", "12", "Stage", "4"));

        assertEquals(12, data.safePadSeconds);
        assertEquals(4, data.stage);
    }

    @Test
    public void doesNotMisclassifyOrdinaryScoreboard() {
        Minecraft18ObservationRules.ScoreboardData data =
                Minecraft18ObservationRules.parseScoreboard(
                        "SkyWars",
                        Arrays.asList("Kills: 3", "Coins: 100"));

        assertFalse(Minecraft18ObservationRules.looksLikeMonsterMaze(data));
        assertEquals(1, data.stage);
        assertEquals(0, data.safePadSeconds);
    }
}
