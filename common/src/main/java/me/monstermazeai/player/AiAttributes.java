package me.monstermazeai.player;

/**
 * Continuous gameplay capability attributes for autonomous Monster Maze players.
 *
 * Values are normalized to [0,1]. They influence decisions and control style;
 * they never alter source Minecraft physics or kit mechanics.
 */
public final class AiAttributes {
    public static final AiAttributes BASELINE = new AiAttributes(
            0.60, // maps to the current 4-tick non-Jumper speeding cadence
            0.50,
            0.50,
            0.50);

    public final double maxSpeed;
    public final double agility;
    public final double handling;
    public final double reactions;

    public AiAttributes(double maxSpeed, double agility, double handling, double reactions) {
        this.maxSpeed = bounded("maxSpeed", maxSpeed);
        this.agility = bounded("agility", agility);
        this.handling = bounded("handling", handling);
        this.reactions = bounded("reactions", reactions);
    }

    /**
     * Maps the Max Speed attribute to the number of server ticks between
     * non-Jumper speeding jump inputs. This is the decision cadence only; the
     * actual horizontal impulse remains the source Jump -10 + sprint-jump
     * interaction in LegacyMovementModel.
     */
    public long nonJumperJumpCadenceTicks() {
        long cadence = 8L - Math.round(maxSpeed * 7.0D);
        return Math.max(1L, Math.min(8L, cadence));
    }

    private static double bounded(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be finite and in [0,1]");
        }
        return value;
    }
}
