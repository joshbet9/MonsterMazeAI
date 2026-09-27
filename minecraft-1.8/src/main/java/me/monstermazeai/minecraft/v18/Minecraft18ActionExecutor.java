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
    private long actionExpiryTick = Long.MIN_VALUE;
    /** First-pad branch commands are one client tick intents; never hold stale movement. */
    private static final long MAX_COMMAND_HOLD_TICKS = 1L;

    public Minecraft18ActionExecutor(Minecraft minecraft) {
        if (minecraft == null) throw new IllegalArgumentException("minecraft");
        this.minecraft = minecraft;
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
                : currentTick + holdTicks;
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

    /** Consume the cursor step once; movement fields remain held until expiry. */
    public synchronized float consumeYawPulse() {
        if (!yawPulsePending) return 0.0f;
        yawPulsePending = false;
        return pendingYawDelta;
    }

    /** Expire a one-tick command after the client tick that consumed it. */
    public synchronized void expireIfNeeded(long currentTick) {
        if (actionExpiryTick != Long.MAX_VALUE && currentTick >= actionExpiryTick) {
            currentAction = LegacyAction.IDLE;
            abilityPulsePending = false;
            yawPulsePending = false;
            pendingYawDelta = 0.0f;
            actionExpiryTick = Long.MIN_VALUE;
        }
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

    public void setAiEnabled(boolean enabled) {
        aiEnabled = enabled;
        if (!enabled) {
            currentAction = LegacyAction.IDLE;
            abilityPulsePending = false;
            yawPulsePending = false;
            pendingYawDelta = 0.0f;
            actionExpiryTick = Long.MIN_VALUE;
        }
    }

    @Override
    public synchronized void releaseAll() {
        currentAction = LegacyAction.IDLE;
        abilityPulsePending = false;
        yawPulsePending = false;
        pendingYawDelta = 0.0f;
        actionExpiryTick = Long.MIN_VALUE;
        System.err.println("[MonsterMazeAI/1.8] EXEC releaseAll()");
    }

    private static String describe(LegacyAction action) {
        return "f=" + action.forward + ",s=" + action.strafe
                + ",jump=" + action.jump + ",sprint=" + action.sprint
                + ",yawDelta=" + action.yawDelta + ",ability=" + action.useAbility;
    }
}
