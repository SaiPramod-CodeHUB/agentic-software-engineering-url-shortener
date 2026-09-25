package com.agentsdlc.orchestrator.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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
 * Decision lineage: <em>why</em> things were done. Kept apart from the audit
 * log (<em>what</em> happened) because the two answer different questions and
 * have different readers — an auditor replays events, a reviewer asks
 * "why did the agent choose version 2?".
 */
public final class DecisionLog {

    /**
     * One decision.
     *
     * @param ts        when it was taken
     * @param runId     run id
     * @param taskId    deciding task
     * @param decision  what was decided
     * @param rationale why
     * @param inputs    the facts the decision was based on
     */
    public record Decision(Instant ts, String runId, String taskId, String decision, String rationale,
                           Map<String, String> inputs) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    private final Clock clock;
    private final List<Decision> decisions = new ArrayList<>();

    /**
     * Creates a decision log backed by a JSON Lines file.
     *
     * @param file  the {@code decisions.jsonl} file
     * @param clock time source
     */
    public DecisionLog(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    /**
     * Records a decision and appends it to the file.
     *
     * @param runId     run id
     * @param taskId    deciding task
     * @param decision  what was decided
     * @param rationale why
     * @param inputs    facts considered
     * @return the recorded decision
     */
    public synchronized Decision record(String runId, String taskId, String decision, String rationale,
                                        Map<String, String> inputs) {
        Decision d = new Decision(clock.instant(), runId, taskId, decision, rationale, Map.copyOf(inputs));
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ts", d.ts().toString());
        line.put("runId", runId);
        line.put("taskId", taskId);
        line.put("decision", decision);
        line.put("rationale", rationale);
        line.put("inputs", new TreeMap<>(inputs));
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, JSON.writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("decision not serialisable", e);
        } catch (IOException e) {
            throw new UncheckedIOException("decision write failed", e);
        }
        decisions.add(d);
        return d;
    }

    /**
     * Returns all recorded decisions.
     *
     * @return immutable copy, in recording order
     */
    public synchronized List<Decision> decisions() {
        return List.copyOf(decisions);
    }
}
