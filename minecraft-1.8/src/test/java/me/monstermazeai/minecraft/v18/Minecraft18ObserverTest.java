package me.monstermazeai.minecraft.v18;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class Minecraft18ObserverTest {
    @Test
    public void rendersNativeScoreboardEntryAndScoreTogether() {
        assertEquals("Safe Pad: 14",
                Minecraft18Observer.formatScoreboardLine("Safe Pad", 14));
        assertEquals("Stage: 3",
                Minecraft18Observer.formatScoreboardLine("Stage", 3));
    }

    @Test
    public void preservesAlreadyFormattedEntryText() {
        assertEquals("Safe Pad: 00:14: 14",
                Minecraft18Observer.formatScoreboardLine("Safe Pad: 00:14", 14));
    }
}
