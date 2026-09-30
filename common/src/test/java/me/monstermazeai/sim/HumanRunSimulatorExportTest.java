package me.monstermazeai.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

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

        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> conditions = new LinkedHashMap<>();
        conditions.put("mode", "speed");
        conditions.put("kit", "REPULSOR");
        conditions.put("pattern", 3);
        conditions.put("profile", "HIGH_SKILL");

        Map<String, Object> simulator = new LinkedHashMap<>();
        simulator.put("stageReached", result.maxStage());
        simulator.put("durationTicks", result.ticks());
        simulator.put("durationSeconds", result.ticks() / 20.0);
        simulator.put("healthRemaining", result.health());
        simulator.put("maxHorizontalSpeed", result.maxHorizontalSpeed());
        simulator.put("maxTickDisplacement", result.maxTickDisplacement());
        simulator.put("firstFallTick", result.firstFallTick());
        simulator.put("firstFallDecision", result.firstFallDecision());

        payload.put("schemaVersion", 1);
        payload.put("conditions", conditions);
        payload.put("simulator", simulator);

        Files.createDirectories(output.toAbsolutePath().getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), payload);

        System.out.println("HUMAN_RUN_SIMULATOR_EXPORT "
                + output.toAbsolutePath()
                + " stage=" + result.maxStage()
                + " ticks=" + result.ticks()
                + " maxSpeed=" + result.maxHorizontalSpeed());
    }
}
