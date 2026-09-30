package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Exports the same deterministic closed-loop simulator used by the authentic
 * acceptance tests in a machine-readable form for human-run comparison.
 */
class HumanRunSimulatorExportTest {
    @Test
    void exportSpeedPattern3RepulsorHighSkill() throws IOException {
        AuthenticStage10SimulationTest.RunResult result =
                AuthenticStage10SimulationTest.run(
                        2, Kit.REPULSOR, AiProfile.HIGH_SKILL, Mode.SPEED);

        Path output = Path.of(System.getProperty(
                "humanRunSimulatorSummary",
                "target/human-run-simulator-summary.json"));

        String json = "{\n"
                + "  \"schemaVersion\": 1,\n"
                + "  \"conditions\": {\"mode\": \"speed\", \"kit\": \"REPULSOR\", \"pattern\": 3, \"profile\": \"HIGH_SKILL\"},\n"
                + "  \"simulator\": {"
                + "\"stageReached\": " + result.maxStage()
                + ", \"durationTicks\": " + result.ticks()
                + ", \"durationSeconds\": " + (result.ticks() / 20.0)
                + ", \"healthRemaining\": " + result.health()
                + ", \"maxHorizontalSpeed\": " + result.maxHorizontalSpeed()
                + ", \"maxTickDisplacement\": " + result.maxTickDisplacement()
                + ", \"firstFallTick\": " + result.firstFallTick()
                + ", \"firstFallDecision\": \"" + escapeJson(result.firstFallDecision()) + "\"}"
                + "\n}\n";

        Files.writeString(output, json);
        System.out.println("HUMAN_RUN_SIMULATOR_EXPORT "
                + output.toAbsolutePath()
                + " stage=" + result.maxStage()
                + " ticks=" + result.ticks()
                + " maxSpeed=" + result.maxHorizontalSpeed());
    }
}
