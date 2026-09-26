package me.monstermazeai.minecraft.v18;

import me.monstermazeai.adapter.LegacyAction;
import me.monstermazeai.adapter.LegacyWorldObservation;
import me.monstermazeai.adapter.LiveMovementValidator;
import net.minecraft.client.Minecraft;
import net.minecraft.util.MovementInputFromOptions;
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
    private net.minecraft.client.entity.EntityPlayerSP controlledPlayer;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        observer = new Minecraft18Observer();
        executor = new Minecraft18ActionExecutor(Minecraft.getMinecraft());
        runtime = new Minecraft18AiRuntime();
        movementValidator = new LiveMovementValidator();
        toggleAi = new net.minecraft.client.settings.KeyBinding(
                "key.monstermazeai.toggle", Keyboard.KEY_F8, "key.categories.monstermazeai");
        ClientRegistry.registerKeyBinding(toggleAi);
        aiEnabled = false;
        executor.setAiEnabled(false);

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
        Minecraft minecraft = Minecraft.getMinecraft();

        if (event.phase != TickEvent.Phase.END || observer == null) {
            return;
        }

        if (minecraft.theWorld == null || minecraft.thePlayer == null) {
            executor.releaseAll();
            executor.setAiEnabled(false);
            controlledPlayer = null;
            movementValidator.reset();
            return;
        }

        ensureMovementInput(minecraft);

        if (toggleAi != null && toggleAi.isPressed()) {
            aiEnabled = !aiEnabled;
            executor.setAiEnabled(aiEnabled);

            if (!aiEnabled) {
                executor.releaseAll();
                movementValidator.reset();
                System.out.println("[MonsterMazeAI/1.8] AI control disabled (F8)");
            } else {
                runtime.startIfConfigured();
                System.out.println("[MonsterMazeAI/1.8] AI control enabled (F8)");
            }
        }

        if (!aiEnabled) {
            movementValidator.reset();
            return;
        }

        LegacyWorldObservation state = observer.observe().state;
        LegacyAction action = runtime.decide(state);

        // Store the command for the next vanilla movement-input update.
        // The custom MovementInput consumes it after Minecraft has read the
        // physical keyboard, so human WASD cannot overwrite the AI command.
        executor.apply(action);

        if (state.inMonsterMaze) {
            movementValidator.observe(state, action);
        } else {
            movementValidator.reset();
        }
    }

    private void ensureMovementInput(Minecraft minecraft) {
        if (minecraft.thePlayer == null) return;
        if (controlledPlayer != minecraft.thePlayer
                || !(minecraft.thePlayer.movementInput instanceof Minecraft18MovementInput)) {
            minecraft.thePlayer.movementInput = new Minecraft18MovementInput(
                    minecraft.gameSettings, minecraft, executor);
            controlledPlayer = minecraft.thePlayer;
            System.out.println("[MonsterMazeAI/1.8] installed authoritative AI MovementInput");
        }
    }
}
