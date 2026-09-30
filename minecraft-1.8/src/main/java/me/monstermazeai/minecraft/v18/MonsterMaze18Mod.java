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
    private FirstPadSpeedrunController firstPadSpeedrun;
    private GameRunSummaryRecorder gameSummary;
    private HumanRunRecorder humanRunRecorder;
    private net.minecraft.client.settings.KeyBinding toggleAi;
    private net.minecraft.client.settings.KeyBinding toggleHumanRecorder;
    private boolean aiEnabled;
    private boolean runEndedLatch;
    private boolean fullRoutingMode;
    private net.minecraft.client.entity.EntityPlayerSP controlledPlayer;
    private long observationLogCount;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        observer = new Minecraft18Observer();
        executor = new Minecraft18ActionExecutor(Minecraft.getMinecraft());
        movementValidator = new LiveMovementValidator();
        runtime = new Minecraft18AiRuntime();
        firstPadSpeedrun = new FirstPadSpeedrunController();
        gameSummary = new GameRunSummaryRecorder();
        firstPadSpeedrun.setTelemetry(gameSummary);
        humanRunRecorder = new HumanRunRecorder(Minecraft.getMinecraft(), observer);

        toggleAi = new net.minecraft.client.settings.KeyBinding(
                "key.monstermazeai.toggle", Keyboard.KEY_F8, "key.categories.monstermazeai");
        ClientRegistry.registerKeyBinding(toggleAi);
        toggleHumanRecorder = new net.minecraft.client.settings.KeyBinding(
                "key.monstermazeai.humanRecorder", Keyboard.KEY_F7, "key.categories.monstermazeai");
        ClientRegistry.registerKeyBinding(toggleHumanRecorder);

        aiEnabled = false;
        runEndedLatch = false;
        fullRoutingMode = false;
        observationLogCount = 0L;
        executor.setAiEnabled(false);

        MinecraftForge.EVENT_BUS.register(observer);
        MinecraftForge.EVENT_BUS.register(this);

        System.out.println("[MonsterMazeAI/1.8] HYBRID mode ready (F8)");
        System.out.println("[MonsterMazeAI/1.8] Human run recorder ready (F7): records actual keyboard/mouse input + live observation to human-runs/*.jsonl");
        System.out.println("[MonsterMazeAI/1.8] First pad uses synchronous optimal speedrun; subsequent pads use normal live AI runtime");
        System.out.println("[MonsterMazeAI/1.8] Per-game GPT summary telemetry enabled");
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();

        if (event.phase != TickEvent.Phase.START || observer == null) {
            return;
        }

        if (toggleHumanRecorder != null && toggleHumanRecorder.isPressed()) {
            boolean enabled = !humanRunRecorder.isEnabled();
            humanRunRecorder.setEnabled(enabled);
            System.out.println("[MonsterMazeAI/1.8] HUMAN RUN RECORDER "
                    + (enabled ? "enabled (will begin when a Monster Maze round is detected)"
                    : "disabled")
                    + (humanRunRecorder.currentPath() == null ? ""
                    : " file=" + humanRunRecorder.currentPath()));
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
            fullRoutingMode = false;
            firstPadSpeedrun.reset();
            observationLogCount = 0L;
            controlledPlayer = null;
            movementValidator.reset();
            if (humanRunRecorder != null) humanRunRecorder.finish("WORLD_LEFT");
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
                fullRoutingMode = false;
                movementValidator.reset();
                System.out.println("[MonsterMazeAI/1.8] HYBRID disabled (F8)");
            } else {
                firstPadSpeedrun.reset();
                fullRoutingMode = false;
                runEndedLatch = false;
                observationLogCount = 0L;
                gameSummary.reset();
                System.out.println("[MonsterMazeAI/1.8] HYBRID enabled (F8): first-pad speedrun -> full routing");
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
                    + " mode=" + (fullRoutingMode ? "FULL_ROUTING" : "FIRST_PAD")
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

        if (Minecraft18RunBoundary.isGameStart(state) && !gameSummary.isActive()) {
            gameSummary.begin(state.worldTick);
        }
        if (gameSummary.isActive() && Minecraft18RunBoundary.isGameEnd(state)) {
            printGameSummary(gameSummary.finish(state.worldTick,
                    state.completed ? "COMPLETED" : (state.alive ? "LEFT_MAZE" : "PLAYER_DEAD")));
            runEndedLatch = true;
            executor.releaseAll();
            executor.setAiEnabled(false);
            aiEnabled = false;
            fullRoutingMode = false;
            firstPadSpeedrun.reset();
            movementValidator.reset();
            return;
        }

        LegacyAction action;
        if (!fullRoutingMode) {
            /*
             * The first-pad controller is intentionally retained as the proven
             * synchronous bootstrap. It owns movement until it geometrically
             * reaches the first active SafePad.
             */
            action = firstPadSpeedrun.next(state);
            executor.applyForTicks(action, state.worldTick, 1L);
            executor.expireIfNeeded(state.worldTick);

            if (firstPadSpeedrun.hasReachedTarget()) {
                /*
                 * Do not ask the normal runtime to make the first-pad journey
                 * again. Start it only after bootstrap completion; its first
                 * observation is therefore the player safely standing on the
                 * first active pad. On the next active-pad transition,
                 * LiveObjectiveController sees the new target and routes away.
                 */
                fullRoutingMode = true;
                runtime.startIfConfigured();
                if (gameSummary != null && gameSummary.isActive()) {
                    gameSummary.controllerEvent(state.worldTick,
                            "[MonsterMazeAI/1.8] FULL ROUTING HANDOFF"
                                    + " tick=" + state.worldTick
                                    + " firstPad=" + state.pad.row + "," + state.pad.column
                                    + " runtime=" + runtime.runtimeStatus());
                }
                System.out.println("[MonsterMazeAI/1.8] FULL ROUTING HANDOFF"
                        + " tick=" + state.worldTick
                        + " firstPad=" + state.pad.row + "," + state.pad.column);
            }
        } else {
            /*
             * Restore the same asynchronous closed-loop runtime that previously
             * drove multi-pad progression. It receives the live state after the
             * first-pad bootstrap instead of competing with the bootstrap
             * controller for ownership of the first leg.
             */
            runtime.submit(state);
            LegacyAction completed = runtime.pollCompleted(state.worldTick);
            if (completed != null) {
                executor.apply(completed, state.worldTick);
            }
            executor.expireIfNeeded(state.worldTick);
            action = executor.currentAction();
        }

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
        if (Minecraft18RunBoundary.isTerminalChat(text)) {
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
            fullRoutingMode = false;
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
