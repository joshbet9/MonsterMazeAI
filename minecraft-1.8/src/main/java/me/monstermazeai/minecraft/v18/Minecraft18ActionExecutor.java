package me.monstermazeai.minecraft.v18;

import me.monstermazeai.adapter.ActionSink;
import me.monstermazeai.adapter.LegacyAction;
import net.minecraft.client.Minecraft;

/**
 * Minecraft 1.8.9 action state bridge.
 *
 * Movement is no longer driven through KeyBinding.setKeyBindState(). Vanilla
 * rebuilds MovementInput from the physical keyboard during EntityPlayerSP's
 * living update, so synthetic key states can be overwritten by the client
 * tick. This class only stores the authoritative AI command; the custom
 * MovementInput consumes it at the exact point vanilla has finished reading
 * physical input.
 */
public final class Minecraft18ActionExecutor implements ActionSink {
    private final Minecraft minecraft;
    private volatile LegacyAction currentAction = LegacyAction.IDLE;
    private volatile boolean aiEnabled;
    private long applyCount;
    private boolean abilityPulsePending;
    private boolean yawPulsePending;
    private float pendingYawDelta;
    private float controlledYaw;
    private boolean controlledYawInitialised;
    private long actionExpiryTick = Long.MIN_VALUE;
    private long jumpExpiryTick = Long.MIN_VALUE;
    /*
     * Live commands are one-tick control intents. The common controller is
     * closed-loop, so reusing an older movement command beyond one client tick
     * changes the state on which the next decision should have been based.
     * Yaw/ability pulses remain one-shot and are never repeated by this hold.
     */
    private static final long MAX_COMMAND_HOLD_TICKS = 1L;

    public Minecraft18ActionExecutor(Minecraft minecraft) {
        if (minecraft == null) throw new IllegalArgumentException("minecraft");
        this.minecraft = minecraft;
    }

    /** Test-only constructor; command lifetime logic is independent of Minecraft itself. */
    Minecraft18ActionExecutor() {
        this.minecraft = null;
    }

    /** Seed the AI camera from the real player exactly once when AI control begins. */
    public synchronized void initialiseControlledYaw(float yaw) {
        controlledYaw = normaliseYaw(yaw);
        controlledYawInitialised = true;
        pendingYawDelta = 0.0F;
        yawPulsePending = false;
    }

    /** Current AI-owned camera heading; physical mouse input is not authoritative while AI is enabled. */
    public synchronized boolean controlledYawInitialised() {
        return controlledYawInitialised;
    }

    public synchronized float controlledYaw() {
        return controlledYawInitialised ? controlledYaw : 0.0F;
    }

    @Override
    public synchronized void apply(LegacyAction action) {
        apply(action, Long.MAX_VALUE, MAX_COMMAND_HOLD_TICKS);
    }

    /** Apply a normal planner result. It is bounded so a stalled planner cannot hold movement forever. */
    public synchronized void apply(LegacyAction action, long currentTick) {
        apply(action, currentTick, MAX_COMMAND_HOLD_TICKS);
    }

    /** Apply a deliberately short-lived result, used only for safe stale-turn recovery. */
    public synchronized void applyForTicks(LegacyAction action, long currentTick, long holdTicks) {
        if (holdTicks < 1L) throw new IllegalArgumentException("holdTicks");
        apply(action, currentTick, holdTicks);
    }

    private synchronized void apply(LegacyAction action, long currentTick, long holdTicks) {
        LegacyAction next = action == null ? LegacyAction.IDLE : action;
        if (next.useAbility && !currentAction.useAbility) abilityPulsePending = true;
        currentAction = next;

        /*
         * forward/strafe/jump/sprint are held inputs for this client tick,
         * while yawDelta is a per-command cursor step. Binding the yaw value
         * to the command snapshot prevents a later command from replacing the
         * pulse before MovementInput consumes it.
         */
        pendingYawDelta = next.yawDelta;
        yawPulsePending = next.yawDelta != 0.0f;

        actionExpiryTick = currentTick == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : currentTick + holdTicks - 1L;
        jumpExpiryTick = next.jump && currentTick != Long.MAX_VALUE
                ? currentTick
                : Long.MIN_VALUE;
        applyCount++;

        if (applyCount == 1 || applyCount % 20 == 0
                || currentAction.forward != 0.0 || currentAction.strafe != 0.0
                || currentAction.jump || currentAction.yawDelta != 0.0f) {
            System.err.println("[MonsterMazeAI/1.8] EXEC command#" + applyCount
                    + " enabled=" + aiEnabled
                    + " action=" + describe(currentAction));
        }
    }

    public synchronized LegacyAction currentAction() {
        return currentAction;
    }

    /**
     * Return the command that should be consumed on this exact world tick.
     * Continuous WASD/sprint intent may be held for a short bounded window when
     * IPC latency is unavoidable, but jump remains a one-tick pulse so the live
     * cadence cannot silently become faster than the simulator.
     */
    public synchronized LegacyAction currentAction(long currentTick) {
        if (currentAction == null) return LegacyAction.IDLE;
        if (actionExpiryTick != Long.MAX_VALUE
                && actionExpiryTick != Long.MIN_VALUE
                && currentTick > actionExpiryTick) {
            return LegacyAction.IDLE;
        }
        boolean jumpActive = jumpExpiryTick != Long.MIN_VALUE && currentTick <= jumpExpiryTick;
        return new LegacyAction(
                currentAction.forward,
                currentAction.strafe,
                jumpActive,
                currentAction.sprint,
                0.0F,
                false);
    }

    /** Consume the cursor step once; movement fields remain held until expiry. */
    public synchronized float consumeYawPulse() {
        if (!yawPulsePending) return 0.0f;
        yawPulsePending = false;
        controlledYaw = normaliseYaw(controlledYaw + pendingYawDelta);
        float delta = pendingYawDelta;
        pendingYawDelta = 0.0F;
        return delta;
    }

    /** Expire a one-tick command after the client tick that consumed it. */
    public synchronized void expireIfNeeded(long currentTick) {
        if (actionExpiryTick != Long.MAX_VALUE && currentTick > actionExpiryTick) {
            currentAction = LegacyAction.IDLE;
            abilityPulsePending = false;
            yawPulsePending = false;
            pendingYawDelta = 0.0f;
            actionExpiryTick = Long.MIN_VALUE;
            jumpExpiryTick = Long.MIN_VALUE;
        }
    }

    /** Drop all AI-owned camera state when AI mode ends. */
    public synchronized void clearControlledYaw() {
        controlledYawInitialised = false;
        pendingYawDelta = 0.0F;
        yawPulsePending = false;
    }

    private static float normaliseYaw(float yaw) {
        while (yaw >= 180.0F) yaw -= 360.0F;
        while (yaw < -180.0F) yaw += 360.0F;
        return yaw;
    }

    /** Called on the Minecraft client thread to consume one right-click pulse. */
    public synchronized boolean consumeAbilityPulse() {
        if (!abilityPulsePending) return false;
        abilityPulsePending = false;
        return true;
    }

    public boolean isAiEnabled() {
        return aiEnabled;
    }

    public synchronized void setAiEnabled(boolean enabled) {
        aiEnabled = enabled;
        // A new AI session must seed its camera from the real player once.
        // Ending AI control returns camera authority to the user.
        controlledYawInitialised = false;
        pendingYawDelta = 0.0F;
        yawPulsePending = false;
        if (!enabled) {
            currentAction = LegacyAction.IDLE;
            abilityPulsePending = false;
            yawPulsePending = false;
            pendingYawDelta = 0.0f;
            actionExpiryTick = Long.MIN_VALUE;
            jumpExpiryTick = Long.MIN_VALUE;
        }
    }

    @Override
    public synchronized void releaseAll() {
        currentAction = LegacyAction.IDLE;
        abilityPulsePending = false;
        yawPulsePending = false;
        pendingYawDelta = 0.0f;
        actionExpiryTick = Long.MIN_VALUE;
        jumpExpiryTick = Long.MIN_VALUE;
        System.err.println("[MonsterMazeAI/1.8] EXEC releaseAll()");
    }

    private static String describe(LegacyAction action) {
        return "f=" + action.forward + ",s=" + action.strafe
                + ",jump=" + action.jump + ",sprint=" + action.sprint
                + ",yawDelta=" + action.yawDelta + ",ability=" + action.useAbility;
    }
}
