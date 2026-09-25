package com.agentsdlc.orchestrator.engine;

import com.agentsdlc.orchestrator.core.TaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Summary of one pipeline run.
 *
 * @param runId          run id
 * @param pipeline       pipeline name
 * @param status         overall outcome
 * @param startedAt      start instant
 * @param durationMillis end-to-end latency of the run
 * @param tasks          per-task results in graph order
 * @param haltReason     why the run stopped early, or {@code null}
 */
public record RunReport(String runId, String pipeline, RunStatus status, Instant startedAt, long durationMillis,
                        List<TaskRecord> tasks, String haltReason) {

    /**
     * Copies the task list.
     *
     * @param runId          run id
     * @param pipeline       pipeline name
     * @param status         outcome
     * @param startedAt      start
     * @param durationMillis duration
     * @param tasks          task records
     * @param haltReason     halt reason
     */
    public RunReport {
        tasks = List.copyOf(tasks);
    }

    /**
     * Looks up a task's record.
     *
     * @param id task id
     * @return the record
     */
    public TaskRecord task(String id) {
        return tasks.stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("no task " + id + " in run " + runId));
    }

    /**
     * Counts tasks with a status.
     *
     * @param status the status
     * @return number of tasks
     */
    public long count(TaskStatus status) {
        return tasks.stream().filter(t -> t.status() == status).count();
    }

    /**
     * One-line-per-task human-readable summary.
     *
     * @return the summary
     */
    public String summary() {
        StringBuilder sb = new StringBuilder("run " + runId + " -> " + status
                + (haltReason == null ? "" : " (" + haltReason + ")") + "\n");
        tasks.forEach(t -> sb.append(String.format("  %-22s %-24s attempts=%d %s%n", t.id(), t.status(),
                t.attempts(), t.reason() == null ? "" : t.reason())));
        return sb.toString();
    }
}
