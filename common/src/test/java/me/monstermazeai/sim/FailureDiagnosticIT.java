package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

class FailureDiagnosticIT {
    @Test
    void printSelectedFailures() {
        record Case(Mode mode, int pattern, Kit kit) {}
        List<Case> cases = List.of(
                new Case(Mode.SPEED, 1, Kit.REPULSOR),
                new Case(Mode.SPEED, 2, Kit.JUMPER),
                new Case(Mode.MODERN, 2, Kit.SLOWBALLER),
                new Case(Mode.MODERN, 1, Kit.BODY_BUILDER));

        for (Case c : cases) {
            AuthenticStage10SimulationTest.RunResult r =
                    AuthenticStage10SimulationTest.run(
                            c.pattern(), c.kit(), AiProfile.BASELINE, c.mode(), 0);

            System.out.println("FAILURE_DIAGNOSTIC mode=" + c.mode()
                    + " pattern=" + (c.pattern() + 1)
                    + " kit=" + c.kit()
                    + " stage=" + r.maxStage()
                    + " ticks=" + r.ticks()
                    + " health=" + r.health()
                    + " firstFallTick=" + r.firstFallTick()
                    + " firstFallPre=" + r.firstFallPreX() + "," + r.firstFallPreY() + "," + r.firstFallPreZ()
                    + " firstFallPreV=" + r.firstFallPreVx() + "," + r.firstFallPreVy() + "," + r.firstFallPreVz()
                    + " firstFall=" + r.firstFallX() + "," + r.firstFallY() + "," + r.firstFallZ()
                    + " firstFallV=" + r.firstFallVx() + "," + r.firstFallVz()
                    + " firstFallDecision=" + r.firstFallDecision()
                    + " finalDecision=" + r.decision());
        }
    }
}
