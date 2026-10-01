package me.monstermazeai.maze;

/**
 * Configurable gap-jump risk model for player route selection.
 *
 * The value is an expected-time-equivalent penalty applied once per
 * one-block gap. It is deliberately separated from route generation so future
 * CPU/player personalities can change risk tolerance without changing the
 * physical movement model.
 *
 * A positive penalty means an equal-length floor route is preferred over a
 * route containing a gap. A gap is only selected when its physical shortcut
 * is valuable enough to overcome the configured risk cost.
 */
public final class GapJumpPolicy {
    /**
     * Baseline: source-valid one-block gaps are throughput shortcuts, so the
     * planner does not add an artificial cost that can cancel their one-edge
     * saving. Finite Jumper charge availability is handled separately by
     * MonsterAwareRoutePlanner.restrictJumperGapBudget().
     *
     * Custom positive costs remain available for controlled experiments where a
     * consumer explicitly wants to trade route length for gap avoidance.
     */
    public static final GapJumpPolicy BASELINE = new GapJumpPolicy(0.0);

    private final double riskCostPerGap;

    public GapJumpPolicy(double riskCostPerGap) {
        if (!Double.isFinite(riskCostPerGap) || riskCostPerGap < 0.0) {
            throw new IllegalArgumentException("riskCostPerGap must be finite and non-negative");
        }
        this.riskCostPerGap = riskCostPerGap;
    }

    public double riskCostPerGap() {
        return riskCostPerGap;
    }

    public double routeCost(int routeSize, int gapCount) {
        if (routeSize < 1 || gapCount < 0) {
            throw new IllegalArgumentException("invalid route metrics");
        }
        return routeSize + (gapCount * riskCostPerGap);
    }
}
