package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import org.junit.jupiter.api.Test;

/**
 * Runs every Modern maze/kit combination until natural termination.
 * This measures the current controller's actual Modern ceiling without a
 * stage-based early exit.
 */
class ModernFullRunDiagnosticTest {
    @Test
    void allModernCasesUntilNaturalTermination() {
        for (int pattern = 0; pattern < 3; pattern++) {
            for (Kit kit : Kit.values()) {
                AuthenticStage10SimulationTest.RunResult result =
                        AuthenticStage10SimulationTest.runToEnd(
                                pattern, kit, AiProfile.HIGH_SKILL, Mode.MODERN);

                boolean naturalEnd = result.firstFallTick() >= 0 || result.ticks() < 100_000;
                System.out.printf(
                        "MODERN_FULL_RUN pattern=%d kit=%s maxStage=%d ticks=%d health=%s firstFallTick=%d "
                                + "terminalTick=%d terminalStage=%d phaseTicks=%d terminalPad=%d,%d terminalOnPad=%s naturalEnd=%s "
                                + "moveShare=%.3f zeroShare=%.3f stationaryShare=%.3f avgSpeed=%.3f "
                                + "lane=%d edge=%d corner=%d steer=%d recovery=%d%n",
                        pattern + 1, kit, result.maxStage(), result.ticks(),
                        Double.toString(result.health()), result.firstFallTick(),
                        result.terminalTick(), result.terminalStage(), result.terminalPhaseTicksRemaining(),
                        result.terminalPadRow(), result.terminalPadColumn(), result.terminalOnPad(),
                        naturalEnd, result.movementInputShare(), result.zeroInputShare(),
                        result.stationaryShare(), result.averageHorizontalSpeed(),
                        result.laneRecoveryTicks(), result.edgeGuardTicks(),
                        result.cornerVectorTicks(), result.steerDriveTicks(),
                        result.fastRecoveryRouteTicks());
            }
        }
    }
}
