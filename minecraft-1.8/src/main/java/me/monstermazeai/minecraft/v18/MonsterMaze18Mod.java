package me.monstermazeai.minecraft.v18;

import me.monstermazeai.adapter.LegacyAction;
import me.monstermazeai.adapter.LegacyWorldObservation;
import me.monstermazeai.adapter.LiveMovementValidator;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import org.lwjgl.input.Keyboard;

@Mod(
        modid = MonsterMaze18Mod.MOD_ID,
        name = "Monster Maze AI",
        version = "0.1.0-SNAPSHOT",
        clientSideOnly = true
)
public final class MonsterMaze18Mod {
    public static final String MOD_ID = "monstermazeai";

    private Minecraft18Observer observer;
    private Minecraft18ActionExecutor executor;
    private Minecraft18AiRuntime runtime;
    private LiveMovementValidator movementValidator;
    private net.minecraft.client.settings.KeyBinding toggleAi;
    private boolean aiEnabled;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        observer = new Minecraft18Observer();
        executor = new Minecraft18ActionExecutor(Minecraft.getMinecraft());
        runtime = new Minecraft18AiRuntime();
        movementValidator = new LiveMovementValidator();
        toggleAi = new net.minecraft.client.settings.KeyBinding(
                "key.monstermazeai.toggle", Keyboard.KEY_F8, "key.categories.monstermazeai");
        ClientRegistry.registerKeyBinding(toggleAi);
        aiEnabled = runtime.configured();

        MinecraftForge.EVENT_BUS.register(observer);
        MinecraftForge.EVENT_BUS.register(this);

        if (runtime.configured()) {
            runtime.startIfConfigured();
            System.out.println("[MonsterMazeAI/1.8] live AI runtime configured; closed-loop execution enabled (F8 toggles control)");
        } else {
            System.out.println("[MonsterMazeAI/1.8] observer-only mode; set MONSTERMAZE_AI_RUNTIME_JAR to enable live AI");
        }
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || observer == null) {
            return;
        }

        if (toggleAi != null && toggleAi.isPressed()) {
            aiEnabled = !aiEnabled;
            if (!aiEnabled) {
                executor.releaseAll();
                System.out.println("[MonsterMazeAI/1.8] AI control disabled (F8)");
            } else {
                runtime.startIfConfigured();
                System.out.println("[MonsterMazeAI/1.8] AI control enabled (F8)");
            }
        }

        LegacyWorldObservation state = observer.observe().state;
        LegacyAction action = aiEnabled ? runtime.decide(state) : LegacyAction.IDLE;
        executor.apply(action);
        if (state.inMonsterMaze) {
            movementValidator.observe(state, action);
        } else {
            movementValidator.reset();
        }
    }
}
