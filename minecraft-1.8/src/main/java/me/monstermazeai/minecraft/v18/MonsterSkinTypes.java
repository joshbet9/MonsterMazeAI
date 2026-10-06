package me.monstermazeai.minecraft.v18;

import net.minecraft.entity.Entity;
import net.minecraft.entity.monster.EntityBlaze;
import net.minecraft.entity.monster.EntityCaveSpider;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.monster.EntityEnderman;
import net.minecraft.entity.monster.EntityEndermite;
import net.minecraft.entity.monster.EntityGuardian;
import net.minecraft.entity.monster.EntityMagmaCube;
import net.minecraft.entity.monster.EntityPigZombie;
import net.minecraft.entity.monster.EntitySilverfish;
import net.minecraft.entity.monster.EntitySkeleton;
import net.minecraft.entity.monster.EntitySlime;
import net.minecraft.entity.monster.EntitySnowman;
import net.minecraft.entity.monster.EntitySpider;
import net.minecraft.entity.monster.EntityWitch;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.passive.EntityBat;
import net.minecraft.entity.passive.EntityChicken;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.passive.EntityMooshroom;
import net.minecraft.entity.passive.EntityOcelot;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.passive.EntityRabbit;
import net.minecraft.entity.passive.EntitySheep;
import net.minecraft.entity.passive.EntitySquid;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.passive.EntityWolf;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Source-backed list of every visual mob skin exposed by Monster Maze 1.8.
 *
 * Monster Maze creates one ghost-snowman gameplay entity and changes its
 * client-visible entity registration to one of these vanilla entity types.
 * The AI must therefore treat the gameplay entity as one monster while keeping
 * the observed visual skin separately.
 */
public final class MonsterSkinTypes {
    public static final String GAMEPLAY_TYPE = "monster_maze_monster";
    public static final List<String> SUPPORTED_VISUAL_TYPES = Collections.unmodifiableList(Arrays.asList(
            "creeper", "skeleton", "spider", "zombie", "slime", "zombie_pigman",
            "enderman", "cave_spider", "silverfish", "blaze", "magma_cube", "bat",
            "witch", "endermite", "guardian", "pig", "sheep", "cow", "chicken",
            "squid", "wolf", "mooshroom", "snowman", "ocelot", "horse", "rabbit",
            "villager"
    ));

    private MonsterSkinTypes() {}

    public static String visualType(Entity entity) {
        if (entity instanceof EntityCreeper) return "creeper";
        if (entity instanceof EntitySkeleton) return "skeleton";
        if (entity instanceof EntitySpider) return "spider";
        if (entity instanceof EntityZombie) return "zombie";
        if (entity instanceof EntitySlime) return "slime";
        if (entity instanceof EntityPigZombie) return "zombie_pigman";
        if (entity instanceof EntityEnderman) return "enderman";
        if (entity instanceof EntityCaveSpider) return "cave_spider";
        if (entity instanceof EntitySilverfish) return "silverfish";
        if (entity instanceof EntityBlaze) return "blaze";
        if (entity instanceof EntityMagmaCube) return "magma_cube";
        if (entity instanceof EntityBat) return "bat";
        if (entity instanceof EntityWitch) return "witch";
        if (entity instanceof EntityEndermite) return "endermite";
        if (entity instanceof EntityGuardian) return "guardian";
        if (entity instanceof EntityPig) return "pig";
        if (entity instanceof EntitySheep) return "sheep";
        if (entity instanceof EntityCow) return "cow";
        if (entity instanceof EntityChicken) return "chicken";
        if (entity instanceof EntitySquid) return "squid";
        if (entity instanceof EntityWolf) return "wolf";
        if (entity instanceof EntityMooshroom) return "mooshroom";
        if (entity instanceof EntitySnowman) return "snowman";
        if (entity instanceof EntityOcelot) return "ocelot";
        if (entity instanceof EntityHorse) return "horse";
        if (entity instanceof EntityRabbit) return "rabbit";
        if (entity instanceof EntityVillager) return "villager";
        return null;
    }

    public static boolean isSupported(Entity entity) {
        return visualType(entity) != null;
    }

    public static boolean isSupportedVisualType(String visualType) {
        return SUPPORTED_VISUAL_TYPES.contains(visualType);
    }

    public static String displayName(String visualType) {
        if ("zombie_pigman".equals(visualType)) return "Zombie Pigman";
        if ("snowman".equals(visualType)) return "Snow Golem";
        if ("cave_spider".equals(visualType)) return "Cave Spider";
        if ("magma_cube".equals(visualType)) return "Magma Cube";
        if ("endermite".equals(visualType)) return "Endermite";
        if ("mooshroom".equals(visualType)) return "Mooshroom";
        if ("enderman".equals(visualType)) return "Enderman";
        if ("silverfish".equals(visualType)) return "Silverfish";
        if ("creeper".equals(visualType)) return "Creeper";
        if ("skeleton".equals(visualType)) return "Skeleton";
        if ("spider".equals(visualType)) return "Spider";
        if ("zombie".equals(visualType)) return "Zombie";
        if ("slime".equals(visualType)) return "Slime";
        if ("blaze".equals(visualType)) return "Blaze";
        if ("bat".equals(visualType)) return "Bat";
        if ("witch".equals(visualType)) return "Witch";
        if ("guardian".equals(visualType)) return "Guardian";
        if ("pig".equals(visualType)) return "Pig";
        if ("sheep".equals(visualType)) return "Sheep";
        if ("cow".equals(visualType)) return "Cow";
        if ("chicken".equals(visualType)) return "Chicken";
        if ("squid".equals(visualType)) return "Squid";
        if ("wolf".equals(visualType)) return "Wolf";
        if ("ocelot".equals(visualType)) return "Ocelot";
        if ("horse".equals(visualType)) return "Horse";
        if ("rabbit".equals(visualType)) return "Rabbit";
        if ("villager".equals(visualType)) return "Villager";
        return "";
    }
}
