package me.monstermazeai.player;

/**
 * Deterministic, non-identifying gameplay profile derived only from observed
 * movement/control telemetry. Intended as the foundation for player-specific
 * NPC models; it contains behaviour measurements, not identity data.
 */
public final class PlayerBehaviorProfile {
    public final long samples;
    public final double averageHorizontalSpeed;
    public final double sprintRatio;
    public final double jumpRatio;
    public final double strafeRatio;
    public final double averageTurnPerTick;
    public final double damagePerSecond;
    public final double abilityUseRatePerSecond;
    public final double forwardBias;
    public final double strafeBias;

    public PlayerBehaviorProfile(long samples, double averageHorizontalSpeed, double sprintRatio,
                          double jumpRatio, double strafeRatio, double averageTurnPerTick,
                          double damagePerSecond, double abilityUseRatePerSecond,
                          double forwardBias, double strafeBias) {
        this.samples=samples;
        this.averageHorizontalSpeed=averageHorizontalSpeed;
        this.sprintRatio=sprintRatio;
        this.jumpRatio=jumpRatio;
        this.strafeRatio=strafeRatio;
        this.averageTurnPerTick=averageTurnPerTick;
        this.damagePerSecond=damagePerSecond;
        this.abilityUseRatePerSecond=abilityUseRatePerSecond;
        this.forwardBias=forwardBias;
        this.strafeBias=strafeBias;
    }
}
