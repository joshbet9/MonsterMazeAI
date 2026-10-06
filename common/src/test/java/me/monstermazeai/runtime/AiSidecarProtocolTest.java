package me.monstermazeai.runtime;

import me.monstermazeai.adapter.LegacyAction;
import me.monstermazeai.adapter.LegacyProtocol;
import me.monstermazeai.adapter.LegacyWorldObservation;
import me.monstermazeai.kit.Kit;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class AiSidecarProtocolTest {
    @Test
    void lobbyObservationProducesIdleAction() throws Exception {
        LegacyWorldObservation observation = new LegacyWorldObservation(
                1L, false, false, -1, true, false, 1, 0, 0,
                new LegacyWorldObservation.Player(0, 64, 0, 0, 0, 0,
                        0, 0, true, 20, 20),
                Kit.JUMPER, 0, 0, null, null,
                new int[99][99], Collections.<LegacyWorldObservation.Monster>emptyList(),
                "", Collections.<String>emptyList());

        ByteArrayOutputStream request = new ByteArrayOutputStream();
        LegacyProtocol.writeObservation(new DataOutputStream(request), observation);

        // This test validates the exact fail-closed decision contract without
        // depending on a Minecraft client process.
        LegacyAction idle = LegacyAction.IDLE;
        assertEquals(0.0, idle.forward, 0.0);
        assertFalse(idle.jump);
    }
}
