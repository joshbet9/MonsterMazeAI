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
    private Minecraft18AiRuntime runtime;
    private GameRunSummaryRecorder gameSummary;
    private HumanRunRecorder humanRunRecorder;
    private net.minecraft.client.settings.KeyBinding toggleAi;
    private net.minecraft.client.settings.KeyBinding toggleHumanRecorder;
    private boolean aiEnabled;
    private boolean runEndedLatch;
    private boolean fullRoutingPrimed;
    private net.minecraft.client.entity.EntityPlayerSP controlledPlayer;
    private long observationLogCount;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        observer = new Minecraft18Observer();
        executor = new Minecraft18ActionExecutor(Minecraft.getMinecraft());
        movementValidator = new LiveMovementValidator();
        runtime = new Minecraft18AiRuntime();
        /*
         * Prewarm the Java-17 sidecar before the first F8/gameplay tick. Startup
         * is asynchronous inside Minecraft18AiRuntime, so this never blocks the
         * Minecraft client thread, but it removes the avoidable first-round
         * dead period seen when AI was enabled only after the round had begun.
         */
        runtime.startIfConfigured();
        gameSummary = new GameRunSummaryRecorder();
        humanRunRecorder = new HumanRunRecorder(Minecraft.getMinecraft(), observer);

        toggleAi = new net.minecraft.client.settings.KeyBinding(
                "key.monstermazeai.toggle", Keyboard.KEY_F8, "key.categories.monstermazeai");
        ClientRegistry.registerKeyBinding(toggleAi);
        toggleHumanRecorder = new net.minecraft.client.settings.KeyBinding(
                "key.monstermazeai.humanRecorder", Keyboard.KEY_F7, "key.categories.monstermazeai");
        ClientRegistry.registerKeyBinding(toggleHumanRecorder);

        aiEnabled = false;
        runEndedLatch = false;
        fullRoutingPrimed = false;
        observationLogCount = 0L;
        executor.setAiEnabled(false);

        MinecraftForge.EVENT_BUS.register(observer);
        MinecraftForge.EVENT_BUS.register(this);

        System.out.println("[MonsterMazeAI/1.8] FULL ROUTING mode ready (F8)");
        System.out.println("[MonsterMazeAI/1.8] Human run recorder ready (F7): actual keyboard/mouse input + live observation -> human-runs/*.jsonl");
        System.out.println("[MonsterMazeAI/1.8] Uses the common full-routing autonomous controller from game start");
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
            observationLogCount = 0L;
            controlledPlayer = null;
            movementValidator.reset();
            return;
        }

        ensureMovementInput(minecraft);

        if (toggleHumanRecorder != null && toggleHumanRecorder.isPressed()) {
            boolean enabled = !humanRunRecorder.isEnabled();
            humanRunRecorder.setEnabled(enabled);
            System.out.println("[MonsterMazeAI/1.8] HUMAN RUN RECORDER "
                    + (enabled ? "enabled (will begin when a Monster Maze round is detected)"
                    : "disabled")
                    + (humanRunRecorder.currentPath() == null ? ""
                    : " file=" + humanRunRecorder.currentPath()));
        }

        if (toggleAi != null && toggleAi.isPressed()) {
            aiEnabled = !aiEnabled;
            executor.setAiEnabled(aiEnabled);

            if (!aiEnabled) {
                if (gameSummary != null && gameSummary.isActive()) {
                    printGameSummary(gameSummary.finish(
                            minecraft.theWorld.getTotalWorldTime(), "AI_DISABLED"));
                }
                executor.releaseAll();
                fullRoutingPrimed = false;
                runtime.stop();
                movementValidator.reset();
                System.out.println("[MonsterMazeAI/1.8] FULL ROUTING disabled (F8)");
            } else {
                fullRoutingPrimed = false;
                runEndedLatch = false;
                observationLogCount = 0L;
                runtime.startIfConfigured();
                gameSummary.reset();
                System.out.println("[MonsterMazeAI/1.8] FULL ROUTING enabled (F8): common controller from game start");
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
            System.out.println("[MonsterMazeAI/1.8] OBS#" + observationLogCount
                    + " mode=FULL_ROUTING"
                    + " sourceMode=" + state.mode
                    + " tick=" + state.worldTick
                    + " inMaze=" + state.inMonsterMaze
                    + " detected=" + state.mazeDetected
                    + " center=" + (state.center == null ? "none"
                        : state.center.x + "," + state.center.y + "," + state.center.z)
                    + " player=" + format(state.player.x) + "," + format(state.player.z)
                    + " pad=" + (state.pad == null ? "none"
                        : state.pad.row + "," + state.pad.column
                            + " reached=" + state.pad.reached)
                    + " monsters=" + state.monsters.size());
        }

        if (state.inMonsterMaze && !gameSummary.isActive()) {
            gameSummary.begin(state.worldTick);
        }

        /*
         * Never start the sidecar or perform a blocking planner call on the
         * Minecraft client thread. The runtime owns background startup and
         * planner workers; this tick only publishes the observation and polls
         * a completed action.
         */
        runtime.startIfConfigured();
        runtime.submit(state);
        LegacyAction completed = runtime.pollCompleted(state.worldTick);
        if (completed != null) {
            executor.apply(completed, state.worldTick);
        }
        executor.expireIfNeeded(state.worldTick);
        LegacyAction action = executor.currentAction(state.worldTick);

        if (state.inMonsterMaze) {
            movementValidator.observe(state, executor.currentAction());
            gameSummary.observe(state, executor.currentAction());
        } else {
            movementValidator.reset();
        }
    }

    @SubscribeEvent
    public void clientTickEnd(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || executor == null) return;
        /*
         * Restore physical keyboard state only after EntityPlayerSP has finished
         * its sprint decision and movement for this tick.
         */
        executor.restoreSyntheticSprintKey();
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
            fullRoutingPrimed = false;
            runtime.stop();
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
