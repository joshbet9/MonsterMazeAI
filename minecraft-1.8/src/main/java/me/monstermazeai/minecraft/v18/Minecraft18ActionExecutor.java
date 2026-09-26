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

    public Minecraft18ActionExecutor(Minecraft minecraft) {
        if (minecraft == null) throw new IllegalArgumentException("minecraft");
        this.minecraft = minecraft;
    }

    @Override
    public synchronized void apply(LegacyAction action) {
        currentAction = action == null ? LegacyAction.IDLE : action;
        applyCount++;

        if (applyCount == 1 || applyCount % 20 == 0
                || currentAction.forward != 0.0 || currentAction.strafe != 0.0
                || currentAction.jump || currentAction.yawDelta != 0.0f) {
            System.err.println("[MonsterMazeAI/1.8] EXEC command#" + applyCount
                    + " enabled=" + aiEnabled
                    + " action=" + describe(currentAction));
        }
    }

    public LegacyAction currentAction() {
        return currentAction;
    }

    public boolean isAiEnabled() {
        return aiEnabled;
    }

    public void setAiEnabled(boolean enabled) {
        aiEnabled = enabled;
        if (!enabled) currentAction = LegacyAction.IDLE;
    }

    @Override
    public synchronized void releaseAll() {
        currentAction = LegacyAction.IDLE;
        System.err.println("[MonsterMazeAI/1.8] EXEC releaseAll()");
    }

    private static String describe(LegacyAction action) {
        return "f=" + action.forward + ",s=" + action.strafe
                + ",jump=" + action.jump + ",sprint=" + action.sprint
                + ",yawDelta=" + action.yawDelta + ",ability=" + action.useAbility;
    }
}
