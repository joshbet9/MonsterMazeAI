package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import org.junit.jupiter.api.Test;

/**
 * Runs every Speed maze/kit combination until natural termination.
 *
 * A run stops only when the simulated player dies/leaves the active game, or
 * when the simulator's large safety tick cap is reached. There is deliberately
 * no stage-based early exit here: the reported stage is the actual run ceiling.
 */
class SpeedFullRunDiagnosticTest {
    @Test
    void allSpeedCasesUntilNaturalTermination() {
        for (int pattern = 0; pattern < 3; pattern++) {
            for (Kit kit : Kit.values()) {
                AuthenticStage10SimulationTest.RunResult result =
                        AuthenticStage10SimulationTest.runToEnd(
                                pattern, kit, AiProfile.HIGH_SKILL, Mode.SPEED);

                boolean naturallyTerminated = result.terminalTick() >= 0;
                System.out.printf(
                        "SPEED_FULL_RUN pattern=%d kit=%s maxStage=%d ticks=%d health=%s pos=%.3f,%.3f "
                                + "firstFallTick=%d terminalTick=%d terminalStage=%d phaseTicks=%d "
                                + "terminalPad=%d,%d terminalOnPad=%s naturalEnd=%s "
                                + "moveShare=%.3f zeroShare=%.3f stationaryShare=%.3f avgSpeed=%.3f "
                                + "lane=%d edge=%d corner=%d steer=%d recovery=%d%n",
                        pattern + 1, kit, result.maxStage(), result.ticks(),
                        Double.toString(result.health()), result.x(), result.z(), result.firstFallTick(),
                        result.terminalTick(), result.terminalStage(), result.terminalPhaseTicksRemaining(),
                        result.terminalPadRow(), result.terminalPadColumn(), result.terminalOnPad(),
                        naturallyTerminated, result.movementInputShare(), result.zeroInputShare(),
                        result.stationaryShare(), result.averageHorizontalSpeed(),
                        result.laneRecoveryTicks(), result.edgeGuardTicks(),
                        result.cornerVectorTicks(), result.steerDriveTicks(),
                        result.fastRecoveryRouteTicks()); 
                System.out.println("TERMINAL_DECISION " + result.terminalDecision().replace(' ', '_'));
            }
        }
    }
}
