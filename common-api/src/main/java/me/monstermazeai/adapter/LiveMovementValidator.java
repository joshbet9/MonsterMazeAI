package me.monstermazeai.adapter;

/**
 * Runtime validator for real-client closed-loop movement.
 * Minecraft remains authoritative for actual physics; this class validates
 * finite, bounded observation transitions and records commanded responses.
 */
public final class LiveMovementValidator {
    private static final double MAX_HORIZONTAL_TICK_DISPLACEMENT = 1.25;
    private LegacyWorldObservation previous;
    private LegacyAction previousAction;
    private long samples, invalidSamples, movingSamples, jumpSamples, abilitySamples;

    public void reset() {
        previous = null; previousAction = null;
        samples = invalidSamples = movingSamples = jumpSamples = abilitySamples = 0L;
    }

    public void observe(LegacyWorldObservation observation, LegacyAction action) {
        if (observation == null || action == null) { invalidSamples++; return; }
        if (previous != null && observation.worldTick <= previous.worldTick) {
            invalidSamples++; previous = observation; previousAction = action; return;
        }
        if (previous != null && previousAction != null
                && observation.inMonsterMaze && previous.inMonsterMaze) {
            samples++;
            double dx = observation.player.x - previous.player.x;
            double dz = observation.player.z - previous.player.z;
            double dy = observation.player.y - previous.player.y;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            if (!finite(dx) || !finite(dz) || !finite(dy)
                    || horizontal > MAX_HORIZONTAL_TICK_DISPLACEMENT) {
                invalidSamples++;
            } else {
                if (horizontal > 1.0e-4
                        && (Math.abs(previousAction.forward) > 1.0e-6
                        || Math.abs(previousAction.strafe) > 1.0e-6)) movingSamples++;
                if (previousAction.jump) jumpSamples++;
                if (previousAction.useAbility) abilitySamples++;
            }
        }
        previous = observation;
        previousAction = action;
    }

    public boolean healthy() { return invalidSamples == 0; }
    public long samples() { return samples; }
    public long invalidSamples() { return invalidSamples; }
    public long movingSamples() { return movingSamples; }
    public long jumpSamples() { return jumpSamples; }
    public long abilitySamples() { return abilitySamples; }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
