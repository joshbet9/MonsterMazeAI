package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Exports a long-horizon authentic closed-loop simulator run for direct
 * comparison with a recorded human run. Conditions are supplied as Maven
 * system properties so CI can match the simulator to the discovered run.
 */
class HumanRunSimulatorExportTest {
    @Test
    void exportMatchingLongHorizonRun() throws IOException {
        Assumptions.assumeTrue(
                Boolean.getBoolean("humanRunComparison"),
                "long-horizon comparison export is enabled by comparison CI only");

        Mode mode = Mode.valueOf(System.getProperty("humanRunMode", "SPEED").toUpperCase(Locale.ROOT));
        Kit kit = Kit.valueOf(System.getProperty("humanRunKit", "MAVERICK").toUpperCase(Locale.ROOT));
        int recorderPattern = Integer.parseInt(System.getProperty("humanRunPattern", "3"));
        if (recorderPattern < 1 || recorderPattern > 3) {
            throw new IllegalArgumentException("humanRunPattern must be 1..3");
        }
        String profileName = System.getProperty("humanRunProfile", "HIGH_SKILL")
                .toUpperCase(Locale.ROOT);
        AiProfile profile = profileForName(profileName);

        AuthenticStage10SimulationTest.RunResult result =
                AuthenticStage10SimulationTest.runLong(
                        recorderPattern - 1, kit, profile, mode);

        Path output = Path.of(System.getProperty(
                "humanRunSimulatorSummary",
                "target/human-run-simulator-summary.json"));

        String json = "{\n"
                + "  \"schemaVersion\": 1,\n"
                + "  \"conditions\": {\"mode\": \"" + mode.name().toLowerCase(Locale.ROOT)
                + "\", \"kit\": \"" + kit.name()
                + "\", \"pattern\": " + recorderPattern
                + ", \"profile\": \"" + profileName + "\"},\n"
                + "  \"simulator\": {"
                + "\"stageReached\": " + result.maxStage()
                + ", \"durationTicks\": " + result.ticks()
                + ", \"durationSeconds\": " + (result.ticks() / 20.0)
                + ", \"healthRemaining\": " + result.health()
                + ", \"damageTaken\": " + result.damageTaken()
                + ", \"damagePerStage\": " + (result.damageTaken() / Math.max(result.maxStage(), 1))
                + ", \"maxHorizontalSpeed\": " + result.maxHorizontalSpeed()
                + ", \"maxTickDisplacement\": " + result.maxTickDisplacement()
                + ", \"firstFallTick\": " + result.firstFallTick()
                + ", \"firstFallDecision\": \"" + escapeJson(result.firstFallDecision()) + "\""
                + ", \"alive\": " + result.alive()
                + ", \"completed\": " + result.completed()
                + ", \"maxTicks\": 20000"
                + ", \"termination\": \"" + (result.completed()
                    ? "COMPLETED" : result.alive() ? "MAX_TICKS" : "DEAD") + "\""
                + ", \"longHorizon\": true}"
                + "\n}\n";

        Files.writeString(output, json);
        System.out.println("HUMAN_RUN_SIMULATOR_EXPORT "
                + output.toAbsolutePath()
                + " mode=" + mode
                + " kit=" + kit
                + " pattern=" + recorderPattern
                + " profile=" + profile
                + " stage=" + result.maxStage()
                + " ticks=" + result.ticks()
                + " maxSpeed=" + result.maxHorizontalSpeed()
                + " damageTaken=" + result.damageTaken()
                + " termination=" + (result.completed()
                    ? "COMPLETED" : result.alive() ? "MAX_TICKS" : "DEAD"));
    }

    private static AiProfile profileForName(String name) {
        return switch (name) {
            case "BASELINE" -> AiProfile.BASELINE;
            case "HIGH_SKILL" -> AiProfile.HIGH_SKILL;
            default -> throw new IllegalArgumentException("Unsupported humanRunProfile: " + name);
        };
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ");
    }
}
