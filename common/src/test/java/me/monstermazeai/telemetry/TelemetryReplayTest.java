package me.monstermazeai.telemetry;

import me.monstermazeai.adapter.LegacyAction;
import me.monstermazeai.adapter.LegacyWorldObservation;
import me.monstermazeai.kit.Kit;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryReplayTest {
    @Test void telemetryProducesOneValidJsonLine() {
        LegacyWorldObservation o = observation(42);
        me.monstermazeai.game.GameState s =
                me.monstermazeai.adapter.ObservationWorldModel.from(o);
        String json = new TelemetryEvent(42, 1234, s,
                new LegacyAction(1, -1, true, false, 5, true), "immediate threat").toJson();
        assertTrue(json.startsWith("{"));
        assertTrue(json.endsWith("}"));
        assertTrue(json.contains("\"tick\":42"));
        assertTrue(json.contains("\"useAbility\":true"));
    }

    @Test void replayRoundTripsObservationAndAction() throws Exception {
        Path p = Files.createTempFile("monster-maze-ai", ".replay");
        try {
            LegacyWorldObservation o = observation(77);
            LegacyAction a = new LegacyAction(.5, -.25, true, true, 7, false);
            try (ReplayRecorder w = new ReplayRecorder(p)) { w.record(o, a); }
            try (ReplayPlayer r = new ReplayPlayer(p)) {
                ReplayPlayer.Frame f = r.next();
                assertNotNull(f);
                assertEquals(77, f.observation.worldTick);
                assertEquals(.5, f.action.forward, 1e-9);
                assertTrue(f.action.jump);
                assertEquals(7, f.action.yawDelta, 1e-6);
                assertNull(r.next());
            }
        } finally { Files.deleteIfExists(p); }
    }

    private static int[][] openMaze() { int[][] m=new int[99][99]; for(int r=0;r<99;r++) java.util.Arrays.fill(m[r],1); return m; }

    private static LegacyWorldObservation observation(long tick) {
        LegacyWorldObservation.Player p = new LegacyWorldObservation.Player(
                24,14,16,0,0,0,0,0,true,20,20);
        return new LegacyWorldObservation(tick,true,true,3,true,false,1,10,2,p,Kit.JUMPER,
                2,0,new LegacyWorldObservation.BlockPoint(23,13,15),
                new LegacyWorldObservation.Pad(50,50,0,false),
                openMaze(), Collections.emptyList(),"Monster Maze",
                Collections.emptyList());
    }
}
