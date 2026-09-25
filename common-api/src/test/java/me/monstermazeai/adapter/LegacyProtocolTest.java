package me.monstermazeai.adapter;

import me.monstermazeai.kit.Kit;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class LegacyProtocolTest {
    @Test
    void roundTripsLiveObservationAndAction() throws Exception {
        LegacyWorldObservation source = new LegacyWorldObservation(
                42L, true, true, 3, true, false, 2, 17, 9,
                new LegacyWorldObservation.Player(24.2, 14.0, 16.7, 0.1, 0.0, -0.2,
                        12.5f, -3.0f, true, 18.0, 20.0),
                Kit.JUMPER, 2, 1,
                new LegacyWorldObservation.BlockPoint(23, 13, 15),
                new LegacyWorldObservation.Pad(50, 51, 1.25, false),
                new int[][]{{1, 0}, {2, 5}},
                Arrays.asList(new LegacyWorldObservation.Monster(
                        7, "monster_maze_monster", "villager",
                        25.0, 14.0, 16.0, 0.1, 0.0, 0.0, false)),
                "Monster Maze", Arrays.asList("Stage", "2"));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        LegacyProtocol.writeObservation(out, source);
        out.flush();

        LegacyWorldObservation copy = LegacyProtocol.readObservation(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));

        assertEquals(source.worldTick, copy.worldTick);
        assertEquals(source.mazePattern, copy.mazePattern);
        assertEquals(source.center.x, copy.center.x);
        assertEquals(source.pad.column, copy.pad.column);
        assertEquals(source.monsters.get(0).visualType, copy.monsters.get(0).visualType);
        assertEquals(2, copy.maze[1][0]);
    }

    @Test
    void roundTripsAction() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        LegacyAction source = new LegacyAction(1.0, -0.5, true, true, 12.5f, true);
        LegacyProtocol.writeAction(out, source);

        LegacyAction copy = LegacyProtocol.readAction(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals(source.forward, copy.forward, 0.0);
        assertEquals(source.strafe, copy.strafe, 0.0);
        assertEquals(source.jump, copy.jump);
        assertEquals(source.yawDelta, copy.yawDelta);
        assertEquals(source.useAbility, copy.useAbility);
    }
}
