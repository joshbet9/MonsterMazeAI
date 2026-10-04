package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast development smoke gate.
 *
 * This is deliberately not a matrix/progress metric. It exercises a small
 * representative set of historically fragile cells to catch catastrophic
 * routing/movement regressions before the expensive natural-termination matrix.
 */
class BottleneckSmokeSimulationTest {
    @Test
    void representativeBottlenecksReachValidatedCheckpoints() {
        java.util.ArrayList<String> failures = new java.util.ArrayList<>();

        failures.add(run("SPEED_P3_MAVERICK", 2, Kit.MAVERICK, Mode.SPEED, 5, 5));
        failures.add(run("MODERN_P1_REPULSOR", 0, Kit.REPULSOR, Mode.MODERN, 5, 5));
        failures.add(run("MODERN_P2_REPULSOR", 1, Kit.REPULSOR, Mode.MODERN, 10, 10));
        failures.add(run("MODERN_P3_SLOWBALLER", 2, Kit.SLOWBALLER, Mode.MODERN, 10, 9));

        failures.removeIf(java.util.Objects::isNull);
        assertTrue(failures.isEmpty(), String.join(System.lineSeparator(), failures));
    }

    private static String compact(String value) {
        if (value == null || value.isBlank()) return "NONE";
        String normalized = value.replace('\n', ' ').replace('\r', ' ');
        return normalized.length() <= 900 ? normalized : normalized.substring(normalized.length() - 900);
    }

    private static String run(String name, int pattern, Kit kit, Mode mode, int targetStage,
                              int requiredStage) {
        AuthenticStage10SimulationTest.RunResult result =
                AuthenticStage10SimulationTest.runDiagnostic(
                        pattern, kit, AiProfile.HIGH_SKILL, mode, targetStage);

        System.out.printf(
                "BOTTLENECK_SMOKE case=%s mode=%s pattern=%d kit=%s stage=%d ticks=%d health=%s " +
                        "avgSpeed=%.3f moveShare=%.3f zeroShare=%.3f stationaryShare=%.3f " +
                        "edge=%d corner=%d steer=%d recovery=%d firstFallTick=%d terminalTick=%d " +
                        "firstFallV=%.3f,%.3f firstFallDecision=%s terminalDecision=%s%n",
                name, mode, pattern + 1, kit, result.maxStage(), result.ticks(),
                Double.toString(result.health()), result.averageHorizontalSpeed(),
                result.movementInputShare(), result.zeroInputShare(),
                result.stationaryShare(), result.edgeGuardTicks(),
                result.cornerVectorTicks(), result.steerDriveTicks(),
                result.fastRecoveryRouteTicks(), result.firstFallTick(),
                result.terminalTick(), result.firstFallVx(), result.firstFallVz(),
                compact(result.firstFallDecision()), compact(result.terminalDecision()));

        if (result.maxStage() < requiredStage) {
            return "Smoke regression: " + name + " reached only stage "
                    + result.maxStage() + " before required stage " + requiredStage;
        }
        return null;
    }
}
