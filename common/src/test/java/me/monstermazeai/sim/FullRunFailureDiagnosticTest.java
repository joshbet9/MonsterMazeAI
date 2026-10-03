package me.monstermazeai.sim;

import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import org.junit.jupiter.api.Test;

class FullRunFailureDiagnosticTest {
    @Test
    void printLowCeilingTerminalTraces() {
        record Case(Mode mode, int pattern, Kit kit) {}
        Case[] cases = {
                new Case(Mode.MODERN, 1, Kit.JUMPER),
                new Case(Mode.MODERN, 1, Kit.SLOWBALLER),
                new Case(Mode.MODERN, 2, Kit.REPULSOR),
                new Case(Mode.MODERN, 3, Kit.JUMPER),
                new Case(Mode.MODERN, 3, Kit.BODY_BUILDER),
                new Case(Mode.MODERN, 3, Kit.REPULSOR),
                new Case(Mode.SPEED, 1, Kit.JUMPER),
                new Case(Mode.SPEED, 3, Kit.JUMPER),
                new Case(Mode.SPEED, 2, Kit.MAVERICK)
        };

        for (Case c : cases) {
            AuthenticStage10SimulationTest.RunResult r =
                    AuthenticStage10SimulationTest.runToEnd(
                            c.pattern - 1, c.kit, AiProfile.HIGH_SKILL, c.mode);
            System.out.println(
                    "DIAG mode=" + c.mode
                            + " pattern=" + c.pattern
                            + " kit=" + c.kit
                            + " maxStage=" + r.maxStage()
                            + " ticks=" + r.ticks()
                            + " health=" + r.health()
                            + " pos=" + r.x() + "," + r.z()
                            + " firstFallTick=" + r.firstFallTick()
                            + " terminalTick=" + r.terminalTick()
                            + " terminalStage=" + r.terminalStage()
                            + " terminalPad=" + r.terminalPadRow() + "," + r.terminalPadColumn()
                            + " terminalOnPad=" + r.terminalOnPad());
            System.out.println("DIAG_LAST_DECISION " + r.decision());
            System.out.println("DIAG_FIRST_FALL " + r.firstFallDecision());
            System.out.println("DIAG_TERMINAL " + r.terminalDecision());
        }
    }
}
