package me.monstermazeai.player;

/**
 * Online player-behaviour measurement. Feed consecutive observed states and
 * the action issued between them. No names, UUIDs, chat, or other identity
 * data are retained.
 */
public final class PlayerBehaviorTracker {
    private PlayerState previous;
    private long previousTick = Long.MIN_VALUE;
    private long samples;
    private double speedSum, sprintCount, jumpCount, strafeCount, turnSum;
    private double damageSum, abilityCount, forwardSum, strafeInputSum;
    private long elapsedTicks;

    public void reset() {
        previous=null; previousTick=Long.MIN_VALUE;
        samples=0; speedSum=sprintCount=jumpCount=strafeCount=turnSum=0;
        damageSum=abilityCount=forwardSum=strafeInputSum=0; elapsedTicks=0;
    }

    public void observe(GameStateLike state, Action action) {
        if (state == null || state.player == null || action == null) return;
        if (previous != null && state.tick <= previousTick) {
            previous = state.player.copy(); previousTick=state.tick; return;
        }
        if (previous != null) {
            long dt = Math.max(1, state.tick - previousTick);
            double dx=state.player.x-previous.x, dz=state.player.z-previous.z;
            double speed=Math.sqrt(dx*dx+dz*dz)/dt;
            speedSum += speed;
            sprintCount += action.sprint ? 1 : 0;
            jumpCount += action.jump ? 1 : 0;
            strafeCount += Math.abs(action.strafe)>1e-6 ? 1 : 0;
            turnSum += Math.abs(action.yawDelta);
            damageSum += Math.max(0, state.player.damageTaken-previous.damageTaken);
            abilityCount += action.useAbility ? 1 : 0;
            forwardSum += action.forward;
            strafeInputSum += action.strafe;
            samples++;
            elapsedTicks += dt;
        }
        previous=state.player.copy(); previousTick=state.tick;
    }

    public PlayerBehaviorProfile profile() {
        if (samples == 0) return new PlayerBehaviorProfile(0,0,0,0,0,0,0,0,0,0);
        double seconds=Math.max(1.0, elapsedTicks/20.0);
        return new PlayerBehaviorProfile(samples, speedSum/samples,
                sprintCount/samples, jumpCount/samples, strafeCount/samples,
                turnSum/samples, damageSum/seconds, abilityCount/seconds,
                forwardSum/samples, strafeInputSum/samples);
    }

    /**
     * Small interface keeps the tracker independent of the full GameState
     * graph and makes it reusable by future replay/player-model pipelines.
     */
    public interface GameStateLike {
        long tick();
        PlayerState player();
    }
}
