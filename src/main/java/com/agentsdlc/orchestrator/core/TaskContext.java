package com.agentsdlc.orchestrator.core;

import com.agentsdlc.orchestrator.audit.AuditLog;
import com.agentsdlc.orchestrator.llm.LlmProvider;
import com.agentsdlc.orchestrator.state.DecisionLog;
import com.agentsdlc.orchestrator.state.StateStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * A task's window onto the run: shared state, its artifact directory, the
 * LLM, and the decision/audit logs. Writes always go to the task's own
 * namespace, so one agent cannot overwrite another's outputs.
 */
public final class TaskContext {

    /** State key prefix under which artifact paths are recorded. */
    public static final String ARTIFACT_PREFIX = "artifact:";

    private final String runId;
    private final TaskSpec task;
    private final StateStore state;
    private final DecisionLog decisions;
    private final AuditLog audit;
    private final LlmProvider llm;
    private final Path workDir;
    private final int attempt;
    private final String lastFailure;
    private final Map<String, String> snapshot;
    private final List<Path> artifacts = new ArrayList<>();

    /**
     * Creates a context; called by the orchestrator for every attempt.
     *
     * @param runId       current run id
     * @param task        the task being executed
     * @param state       shared state store
     * @param decisions   decision lineage log
     * @param audit       audit log
     * @param llm         language model
     * @param workDir     scenario working directory; artifacts are written below it
     * @param attempt     attempt number, starting at 1
     * @param lastFailure message of the previous failed attempt, or {@code null}
     * @param snapshot    the task namespace as it was before the first attempt
     */
    public TaskContext(String runId, TaskSpec task, StateStore state, DecisionLog decisions, AuditLog audit,
                       LlmProvider llm, Path workDir, int attempt, String lastFailure, Map<String, String> snapshot) {
        this.runId = runId;
        this.task = task;
        this.state = state;
        this.decisions = decisions;
        this.audit = audit;
        this.llm = llm;
        this.workDir = workDir;
        this.attempt = attempt;
        this.lastFailure = lastFailure;
        this.snapshot = snapshot;
    }

    /**
     * Returns the run id.
     *
     * @return the run id
     */
    public String runId() {
        return runId;
    }

    /**
     * Returns the task being executed.
     *
     * @return the task spec
     */
    public TaskSpec task() {
        return task;
    }

    /**
     * Returns the attempt number.
     *
     * @return attempt, starting at 1
     */
    public int attempt() {
        return attempt;
    }

    /**
     * Returns why the previous attempt failed, so an agent can adapt.
     *
     * @return the previous failure message, if any
     */
    public Optional<String> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }

    /**
     * Returns the language model.
     *
     * @return the LLM provider
     */
    public LlmProvider llm() {
        return llm;
    }

    /**
     * Returns the scenario working directory.
     *
     * @return working directory
     */
    public Path workDir() {
        return workDir;
    }

    /**
     * Writes an output to this task's namespace.
     *
     * @param key   output key
     * @param value output value
     */
    public void put(String key, String value) {
        state.put(task.id(), key, value);
    }

    /**
     * Reads any namespace.
     *
     * @param namespace namespace (task id or reserved namespace)
     * @param key       key
     * @return the value, if present
     */
    public Optional<String> get(String namespace, String key) {
        return state.get(namespace, key);
    }

    /**
     * Reads a value that must exist (an upstream contract).
     *
     * @param namespace namespace
     * @param key       key
     * @return the value
     * @throws NoSuchElementException when missing
     */
    public String require(String namespace, String key) {
        return state.get(namespace, key)
                .orElseThrow(() -> new NoSuchElementException("missing input " + namespace + "/" + key));
    }

    /**
     * Returns a value from the pre-first-attempt snapshot of this task's
     * namespace (for compensations restoring external state).
     *
     * @param key key
     * @return the snapshotted value, if any
     */
    public Optional<String> snapshotOf(String key) {
        return Optional.ofNullable(snapshot.get(key));
    }

    /**
     * Writes a file below the working directory and registers it as an
     * artifact of this task (scanned by exit gates).
     *
     * @param relativePath path relative to the working directory
     * @param content      UTF-8 text
     * @return the absolute file path
     */
    public Path writeArtifact(String relativePath, String content) {
        Path file = workDir.resolve(relativePath).normalize();
        if (!file.startsWith(workDir.normalize())) {
            throw new IllegalArgumentException("artifact escapes working directory: " + relativePath);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        synchronized (artifacts) {
            artifacts.add(file);
        }
        put(ARTIFACT_PREFIX + relativePath, relativePath);
        return file;
    }

    /**
     * Returns the artifacts written during this attempt.
     *
     * @return artifact paths
     */
    public List<Path> artifacts() {
        synchronized (artifacts) {
            return List.copyOf(artifacts);
        }
    }

    /**
     * Records why a decision was taken (decision lineage).
     *
     * @param decision  what was decided
     * @param rationale why
     * @param inputs    facts considered
     */
    public void decide(String decision, String rationale, Map<String, String> inputs) {
        decisions.record(runId, task.id(), decision, rationale, inputs);
    }

    /**
     * Appends a custom event to the audit log (e.g. incident lifecycle).
     *
     * @param type event type
     * @param data event fields
     */
    public void audit(String type, Map<String, Object> data) {
        audit.record(runId, type, task.id(), data);
    }
}
