package me.monstermazeai.minecraft.v18;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraftforge.common.MinecraftForge;

@Mod(
        modid = MonsterMaze18Mod.MOD_ID,
        name = "Monster Maze AI",
        version = "0.1.0-SNAPSHOT",
        clientSideOnly = true
)
public final class MonsterMaze18Mod {
    public static final String MOD_ID = "monstermazeai";

    private Minecraft18Observer observer;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        observer = new Minecraft18Observer();
        MinecraftForge.EVENT_BUS.register(observer);
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (observer != null) {
            observer.tick();
        }
    }
}
