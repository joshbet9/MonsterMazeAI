package me.monstermazeai.telemetry;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Append-only JSONL recorder; disabled unless explicitly constructed. */
public final class TelemetryRecorder implements Closeable {
    private final BufferedWriter writer;

    public TelemetryRecorder(Path path) throws IOException {
        writer=Files.newBufferedWriter(path, java.nio.charset.StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public synchronized void record(TelemetryEvent event) throws IOException {
        writer.write(event.toJson());
        writer.newLine();
        writer.flush();
    }

    public void close() throws IOException { writer.close(); }
}
