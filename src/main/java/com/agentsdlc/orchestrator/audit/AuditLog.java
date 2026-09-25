package com.agentsdlc.orchestrator.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Append-only audit trail of <em>what happened</em>: one JSON object per line
 * ({@code audit.jsonl}), each with a strictly increasing sequence number.
 *
 * <p>Every event is written and flushed before {@link #record} returns, so a
 * crash loses at most the event being written and never reorders history.
 * JSON Lines keeps the file greppable, streamable and trivially parseable,
 * and a torn last line can be detected and dropped. The sequence number, not
 * the timestamp, defines order — clocks can repeat or step backwards.</p>
 */
public final class AuditLog implements AutoCloseable {

    /**
     * One audit record.
     *
     * @param seq    strictly increasing sequence number
     * @param ts     event time
     * @param runId  run that produced the event, or {@code null}
     * @param type   event type, e.g. {@code TASK_SUCCEEDED}
     * @param taskId task concerned, or {@code null}
     * @param data   event-specific fields
     */
    public record Event(long seq, Instant ts, String runId, String type, String taskId, Map<String, Object> data) {
    }

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final Path file;
    private final Clock clock;
    private final OutputStream out;
    private final List<Event> events = new ArrayList<>();
    private long seq;

    /**
     * Opens (or continues) an audit log.
     *
     * @param file  the {@code .jsonl} file; parent directories are created
     * @param clock time source for event timestamps
     */
    public AuditLog(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            if (Files.exists(file)) {
                try (var lines = Files.lines(file)) {
                    this.seq = lines.filter(l -> !l.isBlank()).count();
                }
            }
            this.out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open audit log " + file, e);
        }
    }

    /**
     * Appends an event and flushes it to the file.
     *
     * @param runId  run id or {@code null}
     * @param type   event type
     * @param taskId task id or {@code null}
     * @param data   event fields; copied with keys sorted
     * @return the recorded event
     */
    public synchronized Event record(String runId, String type, String taskId, Map<String, Object> data) {
        // Keys sorted so identical events serialise identically (Map.of iteration order is randomised).
        Event event = new Event(++seq, clock.instant(), runId, type, taskId, new TreeMap<>(data));
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("seq", event.seq());
        line.put("ts", event.ts().toString());
        line.put("runId", runId);
        line.put("type", type);
        line.put("taskId", taskId);
        line.put("data", event.data());
        try {
            out.write((JSON.writeValueAsString(line) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("audit data is not serialisable: " + type, e);
        } catch (IOException e) {
            throw new UncheckedIOException("audit write failed", e);
        }
        events.add(event);
        return event;
    }

    /**
     * Returns the events recorded through this instance.
     *
     * @return an immutable copy, in sequence order
     */
    public synchronized List<Event> events() {
        return List.copyOf(events);
    }

    /**
     * Returns the backing file.
     *
     * @return the audit file path
     */
    public Path file() {
        return file;
    }

    /**
     * Parses an audit file (for metrics and verification).
     *
     * @param file an {@code audit.jsonl} file
     * @return the events, in file order
     * @throws IOException when the file cannot be read
     */
    @SuppressWarnings("unchecked")
    public static List<Event> read(Path file) throws IOException {
        List<Event> result = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> m = JSON.readValue(line, Map.class);
            result.add(new Event(((Number) m.get("seq")).longValue(), Instant.parse((String) m.get("ts")),
                    (String) m.get("runId"), (String) m.get("type"), (String) m.get("taskId"),
                    (Map<String, Object>) m.get("data")));
        }
        return result;
    }

    @Override
    public synchronized void close() {
        try {
            out.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
