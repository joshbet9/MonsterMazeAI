package me.monstermazeai.minecraft.v18;

import me.monstermazeai.adapter.ActionSink;
import me.monstermazeai.adapter.LegacyAction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

/**
 * Minecraft 1.8.9 execution bridge.
 *
 * Movement is driven through the normal client KeyBinding state, so vanilla
 * movement processing remains authoritative. Camera yaw is applied as a
 * bounded per-tick delta. Ability execution is deliberately not guessed from
 * a kit: the common command exposes the intent, while the 1.8 adapter keeps
 * ability binding opt-in until the real kit bindings are validated.
 */
public final class Minecraft18ActionExecutor implements ActionSink {
    private final Minecraft minecraft;
    private boolean abilityPending;

    public Minecraft18ActionExecutor(Minecraft minecraft) {
        if (minecraft == null) throw new IllegalArgumentException("minecraft");
        this.minecraft = minecraft;
    }

    @Override
    public void apply(LegacyAction action) {
        if (action == null) {
            action = LegacyAction.IDLE;
        }

        set(minecraft.gameSettings.keyBindForward, action.forward > 0.5);
        set(minecraft.gameSettings.keyBindBack, action.forward < -0.5);
        set(minecraft.gameSettings.keyBindRight, action.strafe > 0.5);
        set(minecraft.gameSettings.keyBindLeft, action.strafe < -0.5);
        set(minecraft.gameSettings.keyBindJump, action.jump);
        set(minecraft.gameSettings.keyBindSprint, action.sprint);

        if (minecraft.thePlayer != null && action.yawDelta != 0.0f) {
            minecraft.thePlayer.rotationYaw += clampYaw(action.yawDelta);
        }

        abilityPending = action.useAbility;
    }

    /**
     * True when the planner requested an ability this tick. The flag is
     * intentionally observable but not auto-bound to a mouse/key action yet.
     */
    public boolean isAbilityPending() {
        return abilityPending;
    }

    @Override
    public void releaseAll() {
        set(minecraft.gameSettings.keyBindForward, false);
        set(minecraft.gameSettings.keyBindBack, false);
        set(minecraft.gameSettings.keyBindLeft, false);
        set(minecraft.gameSettings.keyBindRight, false);
        set(minecraft.gameSettings.keyBindJump, false);
        set(minecraft.gameSettings.keyBindSprint, false);
        abilityPending = false;
    }

    private static void set(KeyBinding binding, boolean pressed) {
        KeyBinding.setKeyBindState(binding.getKeyCode(), pressed);
    }

    private static float clampYaw(float delta) {
        return Math.max(-30.0f, Math.min(30.0f, delta));
    }
}
