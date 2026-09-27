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
            /*
             * yawDelta is a per-command cursor step, not a held input. Consume
             * it once while forward/strafe/jump remain continuously authoritative.
             * This permits true simultaneous steering + forward movement without
             * repeatedly applying the same 12-degree correction every tick.
             */
            float yawDelta = executor.consumeYawPulse();
            if (yawDelta != 0.0f) {
                float yawBefore = minecraft.thePlayer.rotationYaw;
                minecraft.thePlayer.rotationYaw += yawDelta;
                while (minecraft.thePlayer.rotationYaw >= 180.0F) {
                    minecraft.thePlayer.rotationYaw -= 360.0F;
                }
                while (minecraft.thePlayer.rotationYaw < -180.0F) {
                    minecraft.thePlayer.rotationYaw += 360.0F;
                }
                System.out.println("[MonsterMazeAI/1.8] EXEC yawPulse=" + yawDelta
                        + " yawBefore=" + yawBefore + " yawAfter=" + minecraft.thePlayer.rotationYaw);
            }

            moveForward = (float) action.forward;
            moveStrafe = (float) action.strafe;
            jump = action.jump;

            // Let vanilla's normal sprint eligibility rules run from the
            // resulting forward input. Explicitly clear sprint when the AI
            // does not request it so a previous sprint cannot leak through.
            minecraft.thePlayer.setSprinting(action.sprint);

            // Monster Maze abilities are right-click item abilities in the source
            // plugin. Send one real item-use pulse per AI ability action; do not
            // synthesize a held mouse state that could repeat every client tick.
            if (executor.consumeAbilityPulse()
                    && minecraft.playerController != null
                    && minecraft.theWorld != null) {
                net.minecraft.item.ItemStack stack = minecraft.thePlayer.getCurrentEquippedItem();
                if (stack != null) {
                    // Source kits use different input events: Repulsor and Body
                    // Rush are right-click abilities; Cryo Blitz is Q-drop.
                    if (stack.getItem() == net.minecraft.init.Items.snowball) {
                        minecraft.thePlayer.dropOneItem(false);
                    } else {
                        minecraft.playerController.sendUseItem(minecraft.thePlayer, minecraft.theWorld, stack);
                    }
                }
            }
        }
    }
}
