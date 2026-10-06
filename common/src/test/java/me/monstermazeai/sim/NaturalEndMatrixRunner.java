package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;

import java.util.Locale;

public final class NaturalEndMatrixRunner {
    private NaturalEndMatrixRunner() {}

    public static void main(String[] args) {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected: <SPEED|MODERN> <pattern 1-3> <KIT>");
        }

        Mode mode = Mode.valueOf(args[0]);
        int pattern = Integer.parseInt(args[1]) - 1;
        Kit kit = Kit.valueOf(args[2]);

        AuthenticStage10SimulationTest.RunResult result =
                AuthenticStage10SimulationTest.run(
                        pattern, kit, AiProfile.HIGH_SKILL, mode, 0, true);

        System.out.printf(
                Locale.ROOT,
                "FULL_MATRIX mode=%s pattern=%d kit=%s stage=%d ticks=%d health=%.2f%n",
                mode, pattern + 1, kit, result.maxStage(), result.ticks(), result.health());
        System.out.printf(
                Locale.ROOT,
                "EFFICIENCY mode=%s pattern=%d kit=%s s5k=%d s10k=%d s15k=%d s20k=%d%n",
                mode, pattern + 1, kit,
                result.stageAt5k(), result.stageAt10k(), result.stageAt15k(), result.stageAt20k());
    }
}
