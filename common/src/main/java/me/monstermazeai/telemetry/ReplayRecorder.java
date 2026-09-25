package me.monstermazeai.telemetry;

import me.monstermazeai.adapter.LegacyAction;
import me.monstermazeai.adapter.LegacyProtocol;
import me.monstermazeai.adapter.LegacyWorldObservation;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Binary replay recorder using the same versioned observation/action wire format. */
public final class ReplayRecorder implements Closeable {
    private final DataOutputStream out;

    public ReplayRecorder(Path path) throws IOException {
        out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)));
    }

    public synchronized void record(LegacyWorldObservation observation, LegacyAction action) throws IOException {
        LegacyProtocol.writeObservation(out, observation);
        LegacyProtocol.writeAction(out, action);
        out.flush();
    }

    public void close() throws IOException { out.close(); }
}
