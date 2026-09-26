package me.monstermazeai.minecraft.v18;

import me.monstermazeai.adapter.LegacyAction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.MovementInputFromOptions;

/**
 * Authoritative AI movement input for Minecraft 1.8.9.
 *
 * MovementInputFromOptions is vanilla's actual input boundary. Minecraft calls
 * updatePlayerMoveState() during EntityPlayerSP.onLivingUpdate(), after
 * reading the physical keyboard and immediately before converting the input
 * into player movement. By overriding the fields here, AI control cannot be
 * lost because a later vanilla step re-reads WASD.
 */
public final class Minecraft18MovementInput extends MovementInputFromOptions {
    private final Minecraft minecraft;
    private final Minecraft18ActionExecutor executor;

    public Minecraft18MovementInput(GameSettings settings,
                                    Minecraft minecraft,
                                    Minecraft18ActionExecutor executor) {
        super(settings);
        if (minecraft == null) throw new IllegalArgumentException("minecraft");
        if (executor == null) throw new IllegalArgumentException("executor");
        this.minecraft = minecraft;
        this.executor = executor;
    }

    @Override
    public void updatePlayerMoveState() {
        // Keep normal keyboard behaviour available whenever AI is disabled.
        super.updatePlayerMoveState();

        if (!executor.isAiEnabled()) return;

        LegacyAction action = executor.currentAction();
        if (action == null) action = LegacyAction.IDLE;

        if (minecraft.thePlayer != null) {
            if (action.yawDelta != 0.0f) {
                minecraft.thePlayer.rotationYaw += action.yawDelta;
                while (minecraft.thePlayer.rotationYaw >= 180.0F) {
                    minecraft.thePlayer.rotationYaw -= 360.0F;
                }
                while (minecraft.thePlayer.rotationYaw < -180.0F) {
                    minecraft.thePlayer.rotationYaw += 360.0F;
                }
            }

            moveForward = (float) action.forward;
            moveStrafe = (float) action.strafe;
            jump = action.jump;

            // Let vanilla's normal sprint eligibility rules run from the
            // resulting forward input. Explicitly clear sprint when the AI
            // does not request it so a previous sprint cannot leak through.
            minecraft.thePlayer.setSprinting(action.sprint);
        }
    }
}
