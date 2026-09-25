package me.monstermazeai.minecraft.v18;

import me.monstermazeai.adapter.ActionSink;
import me.monstermazeai.adapter.LegacyAction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

/**
 * Minecraft 1.8.9 execution bridge with explicit diagnostic tracing.
 */
public final class Minecraft18ActionExecutor implements ActionSink {
    private final Minecraft minecraft;
    private boolean abilityPending;
    private boolean abilityPressed;
    private long applyCount;

    public Minecraft18ActionExecutor(Minecraft minecraft) {
        if (minecraft == null) throw new IllegalArgumentException("minecraft");
        this.minecraft = minecraft;
    }

    @Override
    public void apply(LegacyAction action) {
        if (action == null) action = LegacyAction.IDLE;
        applyCount++;

        boolean forward = action.forward > 0.5;
        boolean back = action.forward < -0.5;
        boolean right = action.strafe > 0.5;
        boolean left = action.strafe < -0.5;
        boolean jump = action.jump;
        boolean sprint = action.sprint;
        boolean useItem = action.useAbility && !abilityPressed;

        float yawBefore = minecraft.thePlayer == null ? Float.NaN : minecraft.thePlayer.rotationYaw;

        set(minecraft.gameSettings.keyBindForward, forward);
        set(minecraft.gameSettings.keyBindBack, back);
        set(minecraft.gameSettings.keyBindRight, right);
        set(minecraft.gameSettings.keyBindLeft, left);
        set(minecraft.gameSettings.keyBindJump, jump);
        set(minecraft.gameSettings.keyBindSprint, sprint);

        if (minecraft.thePlayer != null && action.yawDelta != 0.0f) {
            minecraft.thePlayer.rotationYaw += clampYaw(action.yawDelta);
        }

        abilityPending = action.useAbility;
        set(minecraft.gameSettings.keyBindUseItem, useItem);
        abilityPressed = action.useAbility;

        if (applyCount == 1 || applyCount % 20 == 0 || forward || back || left || right || jump || action.yawDelta != 0.0f) {
            float yawAfter = minecraft.thePlayer == null ? Float.NaN : minecraft.thePlayer.rotationYaw;
            System.err.println("[MonsterMazeAI/1.8] EXEC apply#" + applyCount
                    + " action=" + describe(action)
                    + " keys[fwd=" + forward + ",back=" + back
                    + ",left=" + left + ",right=" + right
                    + ",jump=" + jump + ",sprint=" + sprint
                    + ",use=" + useItem + "]"
                    + " yaw=" + yawBefore + "->" + yawAfter
                    + " player=" + (minecraft.thePlayer == null ? "null"
                        : minecraft.thePlayer.posX + "," + minecraft.thePlayer.posY + "," + minecraft.thePlayer.posZ));
        }
    }

    public boolean isAbilityPending() { return abilityPending; }

    @Override
    public void releaseAll() {
        set(minecraft.gameSettings.keyBindForward, false);
        set(minecraft.gameSettings.keyBindBack, false);
        set(minecraft.gameSettings.keyBindLeft, false);
        set(minecraft.gameSettings.keyBindRight, false);
        set(minecraft.gameSettings.keyBindJump, false);
        set(minecraft.gameSettings.keyBindSprint, false);
        abilityPending = false;
        abilityPressed = false;
        System.err.println("[MonsterMazeAI/1.8] EXEC releaseAll()");
    }

    private static void set(KeyBinding binding, boolean pressed) {
        KeyBinding.setKeyBindState(binding.getKeyCode(), pressed);
    }

    private static float clampYaw(float delta) {
        return Math.max(-30.0f, Math.min(30.0f, delta));
    }

    private static String describe(LegacyAction action) {
        return "f=" + action.forward + ",s=" + action.strafe
                + ",jump=" + action.jump + ",sprint=" + action.sprint
                + ",yawDelta=" + action.yawDelta + ",ability=" + action.useAbility;
    }
}
