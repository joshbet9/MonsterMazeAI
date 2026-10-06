package me.monstermazeai.minecraft.v18;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class Minecraft18ObserverTest {
    @Test
    public void preservesNativeScoreboardTeamTextWithoutAppendingLineNumber() {
        assertEquals("Safe Pad",
                Minecraft18Observer.formatScoreboardLine("Safe Pad"));
        assertEquals("60 Seconds",
                Minecraft18Observer.formatScoreboardLine("60 Seconds"));
        assertEquals("Stage",
                Minecraft18Observer.formatScoreboardLine("Stage"));
    }

    @Test
    public void preservesFormattedVisibleEntryText() {
        assertEquals("Safe Pad: 00:14",
                Minecraft18Observer.formatScoreboardLine("Safe Pad: 00:14"));
    }
}
