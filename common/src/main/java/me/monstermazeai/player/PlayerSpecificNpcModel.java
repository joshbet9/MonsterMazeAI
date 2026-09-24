package me.monstermazeai.player;

import me.monstermazeai.game.GameState;

/**
 * Deterministic policy model for an NPC that emulates measured player
 * tendencies while preserving the base navigation decision.
 *
 * The model never changes the target or maze route. It only shapes controls
 * using the supplied behavioural profile, making it safe to layer on top of
 * the autonomous agent.
 */
public final class PlayerSpecificNpcModel {
    private final PlayerBehaviorProfile profile;

    public PlayerSpecificNpcModel(PlayerBehaviorProfile profile) {
        if (profile == null) throw new IllegalArgumentException("profile");
        this.profile = profile;
    }

    public PlayerBehaviorProfile profile() {
        return profile;
    }

    public Action shape(GameState state, Action base) {
        if (state == null || base == null || profile.samples == 0) return base;

        boolean sprint = base.sprint() || profile.sprintRatio >= 0.5;
        double forward = base.forward();
        double strafe = base.strafe();

        // Preserve navigation direction while biasing mixed movement toward
        // the measured player's preferred input balance.
        if (Math.abs(forward) > 1e-9 || Math.abs(strafe) > 1e-9) {
            double fb = clamp(profile.forwardBias, -1, 1);
            double sb = clamp(profile.strafeBias, -1, 1);
            if (Math.abs(strafe) < 1e-9 && Math.abs(sb) > 0.15) {
                strafe = 0.25 * sb;
            }
            if (Math.abs(forward) < 1e-9 && Math.abs(fb) > 0.15) {
                forward = 0.25 * fb;
            }
        }

        // A measured jump tendency is sampled deterministically from the
        // game tick rather than using runtime randomness, keeping replays
        // reproducible.
        boolean jump = base.jump();
        if (!jump && profile.jumpRatio > 0.0 && state.player != null && state.player.grounded) {
            long period = Math.max(2L, Math.round(1.0 / Math.min(0.5, profile.jumpRatio)));
            jump = state.tick % period == 0;
        }

        return new Action(clamp(forward,-1,1), clamp(strafe,-1,1), jump,
                sprint, base.yawDelta(), base.useAbility());
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
