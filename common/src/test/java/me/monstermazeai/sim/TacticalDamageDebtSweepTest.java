package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import org.junit.jupiter.api.Test;

/**
 * Fast calibration sweep for the tactical damage-vs-progress debt.
 * The source mechanics remain unchanged; only the tactical ranking weight is varied.
 * Run with -Dmonstermaze.tactical.damageDebtPerFourHealth=<value>.
 */
class TacticalDamageDebtSweepTest {
    private static final int[][] CASES = {
            {0, 0, 1}, // Speed Pattern 1 Slowballer
            {0, 1, 3}, // Speed Pattern 2 Repulsor
            {0, 2, 4}, // Speed Pattern 3 Maverick
            {1, 0, 4}, // Modern Pattern 1 Maverick
            {1, 1, 3}, // Modern Pattern 2 Repulsor
            {1, 2, 4}, // Modern Pattern 3 Maverick
    };

    @Test
    void sweepRepresentativeDamageDebtCases() {
        double debt = Double.parseDouble(System.getProperty(
                "monstermaze.tactical.damageDebtPerFourHealth", "0.50"));
        long stageSum = 0;
        long completed = 0;
        for (int[] entry : CASES) {
            Mode mode = entry[0] == 0 ? Mode.SPEED : Mode.MODERN;
            int pattern = entry[1];
            Kit kit = Kit.values()[entry[2]];
            AuthenticStage10SimulationTest.RunResult result =
                    AuthenticStage10SimulationTest.runToEnd(
                            pattern, kit, AiProfile.HIGH_SKILL, mode);
            stageSum += result.maxStage();
            if (result.terminalTick() >= 0) completed++;
            System.out.printf(
                    "DAMAGE_DEBT_SWEEP debt=%.3f mode=%s pattern=%d kit=%s stage=%d ticks=%d health=%.1f firstFall=%d%n",
                    debt, mode, pattern + 1, kit, result.maxStage(), result.ticks(),
                    result.health(), result.firstFallTick());
        }
        System.out.printf(
                "DAMAGE_DEBT_SWEEP_SUMMARY debt=%.3f cases=%d stageSum=%d completed=%d%n",
                debt, CASES.length, stageSum, completed);
    }
}