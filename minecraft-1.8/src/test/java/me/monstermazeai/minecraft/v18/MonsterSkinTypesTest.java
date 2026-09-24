package me.monstermazeai.minecraft.v18;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class MonsterSkinTypesTest {

    @Test
    public void supportedSkinSetMatchesMonsterMazeSource() {
        Assert.assertEquals(27, MonsterSkinTypes.SUPPORTED_VISUAL_TYPES.size());
        Assert.assertEquals(27, new HashSet<String>(MonsterSkinTypes.SUPPORTED_VISUAL_TYPES).size());

        String[] expected = {
                "creeper", "skeleton", "spider", "zombie", "slime", "zombie_pigman",
                "enderman", "cave_spider", "silverfish", "blaze", "magma_cube", "bat",
                "witch", "endermite", "guardian", "pig", "sheep", "cow", "chicken",
                "squid", "wolf", "mooshroom", "snowman", "ocelot", "horse", "rabbit",
                "villager"
        };
        for (String skin : expected) {
            Assert.assertTrue("Missing source-supported skin: " + skin,
                    MonsterSkinTypes.isSupportedVisualType(skin));
        }
    }

    @Test
    public void gameplayIdentityIsIndependentOfVisualSkin() {
        Assert.assertEquals("monster_maze_monster", MonsterSkinTypes.GAMEPLAY_TYPE);
        Assert.assertNotEquals(MonsterSkinTypes.GAMEPLAY_TYPE, "enderman");
        Assert.assertNotEquals(MonsterSkinTypes.GAMEPLAY_TYPE, "zombie");
        Assert.assertNotEquals(MonsterSkinTypes.GAMEPLAY_TYPE, "snowman");
    }
}
