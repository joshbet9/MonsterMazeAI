package me.monstermazeai.minecraft.v18;

import me.monstermazeai.adapter.LegacyAction;
import me.monstermazeai.adapter.LegacyWorldObservation;
import me.monstermazeai.adapter.LiveMovementValidator;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
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
    private LiveMovementValidator movementValidator;
    private FirstPadSpeedrunController firstPadSpeedrun;
    private GameRunSummaryRecorder gameSummary;
    private net.minecraft.client.settings.KeyBinding toggleAi;
    private boolean aiEnabled;
    private boolean runEndedLatch;
    private net.minecraft.client.entity.EntityPlayerSP controlledPlayer;
    private long observationLogCount;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        observer = new Minecraft18Observer();
        executor = new Minecraft18ActionExecutor(Minecraft.getMinecraft());
        movementValidator = new LiveMovementValidator();
        firstPadSpeedrun = new FirstPadSpeedrunController();
        gameSummary = new GameRunSummaryRecorder();
        firstPadSpeedrun.setTelemetry(gameSummary);

        toggleAi = new net.minecraft.client.settings.KeyBinding(
                "key.monstermazeai.toggle", Keyboard.KEY_F8, "key.categories.monstermazeai");
        ClientRegistry.registerKeyBinding(toggleAi);

        aiEnabled = false;
        runEndedLatch = false;
        observationLogCount = 0L;
        executor.setAiEnabled(false);

        MinecraftForge.EVENT_BUS.register(observer);
        MinecraftForge.EVENT_BUS.register(this);

        System.out.println("[MonsterMazeAI/1.8] FIRST_PAD_SPEEDRUN mode ready (F8)");
        System.out.println("[MonsterMazeAI/1.8] No sidecar/planner is used in this benchmark");
        System.out.println("[MonsterMazeAI/1.8] Per-game GPT summary telemetry enabled");
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();

        if (event.phase != TickEvent.Phase.START || observer == null) {
            return;
        }

        if (minecraft.theWorld == null || minecraft.thePlayer == null) {
            if (gameSummary != null && gameSummary.isActive()) {
                printGameSummary(gameSummary.finish(
                        minecraft.theWorld == null ? 0L : minecraft.theWorld.getTotalWorldTime(),
                        "WORLD_LEFT"));
            }
            executor.releaseAll();
            executor.setAiEnabled(false);
            aiEnabled = false;
            firstPadSpeedrun.reset();
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
                if (gameSummary != null && gameSummary.isActive()) {
                    printGameSummary(gameSummary.finish(
                            minecraft.theWorld.getTotalWorldTime(), "AI_DISABLED"));
                }
                executor.releaseAll();
                firstPadSpeedrun.reset();
                movementValidator.reset();
                System.out.println("[MonsterMazeAI/1.8] FIRST_PAD_SPEEDRUN disabled (F8)");
            } else {
                firstPadSpeedrun.reset();
                runEndedLatch = false;
                observationLogCount = 0L;
                gameSummary.reset();
                System.out.println("[MonsterMazeAI/1.8] FIRST_PAD_SPEEDRUN enabled (F8)");
            }
        }

        if (!aiEnabled || runEndedLatch) {
            executor.releaseAll();
            movementValidator.reset();
            return;
        }

        LegacyWorldObservation state = observer.observe().state;
        observationLogCount++;

        if (observationLogCount == 1L || observationLogCount % 20L == 0L) {
            System.out.println("[MonsterMazeAI/1.8] FIRST_PAD_OBS#" + observationLogCount
                    + " tick=" + state.worldTick
                    + " inMaze=" + state.inMonsterMaze
                    + " detected=" + state.mazeDetected
                    + " center=" + (state.center == null ? "none"
                        : state.center.x + "," + state.center.y + "," + state.center.z)
                    + " player=" + format(state.player.x) + "," + format(state.player.z)
                    + " pad=" + (state.pad == null ? "none"
                        : state.pad.row + "," + state.pad.column
                            + " reached=" + state.pad.reached));
        }

        // This benchmark is deliberately synchronous. Route calculation and the
        // movement decision happen on the client thread, then MovementInput
        // consumes the command later in the same Minecraft tick. There is no IPC,
        // Future, stale-action window, command queue, or sidecar latency.
        if (state.inMonsterMaze && !gameSummary.isActive()) {
            gameSummary.begin(state.worldTick);
        }

        LegacyAction action = firstPadSpeedrun.next(state);
        executor.applyForTicks(action, state.worldTick, 1L);
        executor.expireIfNeeded(state.worldTick);

        if (state.inMonsterMaze) {
            movementValidator.observe(state, executor.currentAction());
            gameSummary.observe(state, executor.currentAction());
        } else {
            movementValidator.reset();
        }
    }

    @SubscribeEvent
    public void onClientChat(ClientChatReceivedEvent event) {
        if (!aiEnabled || event == null || event.message == null) return;
        String text = event.message.getUnformattedText();
        if (text == null) return;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("fell off the maze") || lower.contains("solo run over")
                || lower.contains("you weren't on the safe pad")) {
            runEndedLatch = true;

            if (gameSummary != null && gameSummary.isActive()) {
                printGameSummary(gameSummary.finish(
                        Minecraft.getMinecraft().theWorld == null
                                ? 0L
                                : Minecraft.getMinecraft().theWorld.getTotalWorldTime(),
                        "CHAT: " + text));
            }

            aiEnabled = false;
            executor.setAiEnabled(false);
            executor.releaseAll();
            firstPadSpeedrun.reset();
            movementValidator.reset();
            System.out.println("[MonsterMazeAI/1.8] RUN END LATCH chat=\"" + text + "\"");
        }
    }

    private void printGameSummary(String summary) {
        if (summary == null) return;
        System.out.println(summary);
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

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
