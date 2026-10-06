package me.monstermazeai.telemetry;

import me.monstermazeai.adapter.LegacyAction;
import me.monstermazeai.adapter.LegacyProtocol;
import me.monstermazeai.adapter.LegacyWorldObservation;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Sequential replay reader. It never contacts Minecraft or uses live state. */
public final class ReplayPlayer implements Closeable {
    public static final class Frame {
        public final LegacyWorldObservation observation;
        public final LegacyAction action;
        Frame(LegacyWorldObservation observation, LegacyAction action) {
            this.observation=observation; this.action=action;
        }
    }

    private final DataInputStream in;

    public ReplayPlayer(Path path) throws IOException {
        in=new DataInputStream(new BufferedInputStream(Files.newInputStream(path)));
    }

    public Frame next() throws IOException {
        try {
            LegacyWorldObservation observation=LegacyProtocol.readObservation(in);
            return new Frame(observation, LegacyProtocol.readAction(in));
        } catch (EOFException end) {
            return null;
        }
    }

    public void close() throws IOException { in.close(); }
}
