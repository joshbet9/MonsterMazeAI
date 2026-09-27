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
    private long observationLogCount;

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
        observationLogCount = 0L;
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

        if (event.phase != TickEvent.Phase.START || observer == null) {
            return;
        }

        if (minecraft.theWorld == null || minecraft.thePlayer == null) {
            executor.releaseAll();
            executor.setAiEnabled(false);
            observationLogCount = 0L;
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
                System.out.println("[MonsterMazeAI/1.8] AI control enabled (F8) runtime=" + runtime.runtimeStatus());
            }
        }

        if (!aiEnabled) {
            movementValidator.reset();
            return;
        }

        LegacyWorldObservation state = observer.observe().state;
        observationLogCount++;
        if (observationLogCount == 1L || observationLogCount % 20L == 0L) {
            System.out.println("[MonsterMazeAI/1.8] OBS SUBMIT#" + observationLogCount
                    + " tick=" + state.worldTick
                    + " inMaze=" + state.inMonsterMaze
                    + " detected=" + state.mazeDetected
                    + " center=" + (state.center == null ? "none"
                        : state.center.x + "," + state.center.y + "," + state.center.z)
                    + " pad=" + (state.pad == null ? "none"
                        : state.pad.row + "," + state.pad.column + " reached=" + state.pad.reached)
                    + " monsters=" + state.monsters.size());
        }

        // Never block the Minecraft client tick on the planner/sidecar. Submit
        // the newest observation when the previous decision has completed, then
        // apply only completed results. Until a result arrives, the executor
        // retains its last command; this keeps the render/client thread alive.
        runtime.submit(state);
        LegacyAction completed = runtime.pollCompleted(state.worldTick);
        if (completed != null) {
            /*
             * The isolated first-pad branch is deliberately fail-closed:
             * every completed decision belongs to one client tick only.
             * Keeping an old W/jump command alive for 20 ticks is unsafe on a
             * one-block-wide floating maze and was a direct contributor to the
             * previous walk-off-edge failure.
             */
            executor.applyForTicks(completed, state.worldTick, 1L);
        }
        executor.expireIfNeeded(state.worldTick);

        if (state.inMonsterMaze) {
            movementValidator.observe(state, executor.currentAction());
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
