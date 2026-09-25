package com.agentsdlc.orchestrator.metrics;

import com.agentsdlc.orchestrator.audit.AuditLog;
import com.agentsdlc.orchestrator.core.TaskStatus;
import com.agentsdlc.orchestrator.engine.RunReport;
import com.agentsdlc.orchestrator.engine.RunStatus;
import com.agentsdlc.orchestrator.engine.TaskRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Derives metrics from run reports and the audit log. Counters and MTTR come
 * from the audit log — the system of record — so they can be recomputed from
 * the file alone, long after the process is gone.
 */
public final class Metrics {

    private static final Set<TaskStatus> ATTEMPTED = Set.of(TaskStatus.SUCCEEDED, TaskStatus.SUCCEEDED_WITH_FALLBACK,
            TaskStatus.FAILED, TaskStatus.COMPENSATED, TaskStatus.BLOCKED, TaskStatus.REJECTED);

    private Metrics() {
    }

    /**
     * Computes metrics.
     *
     * @param runs   run reports of the scenario
     * @param events audit events of the scenario
     * @return the metrics
     */
    public static MetricsReport compute(List<RunReport> runs, List<AuditLog.Event> events) {
        Map<String, Long> counters = new TreeMap<>();
        counters.put("tasksSucceeded", countEvents(events, "TASK_SUCCEEDED"));
        counters.put("tasksFailed", countEvents(events, "TASK_FAILED"));
        counters.put("tasksBlocked", countEvents(events, "TASK_BLOCKED"));
        counters.put("tasksRejected", countEvents(events, "TASK_REJECTED"));
        counters.put("tasksSkipped", countEvents(events, "TASK_SKIPPED"));
        counters.put("tasksReused", countEvents(events, "TASK_REUSED"));
        counters.put("attempts", countEvents(events, "TASK_ATTEMPT"));
        counters.put("retries", countEvents(events, "TASK_RETRY"));
        counters.put("fallbacks", countEvents(events, "FALLBACK_STARTED"));
        counters.put("rollbacks", events.stream().filter(e -> e.type().equals("ROLLBACK_STEP")
                && "compensated".equals(e.data().get("result"))).count());
        counters.put("safeStops", countEvents(events, "SAFE_STOP"));
        counters.put("replans", countEvents(events, "REPLAN"));
        counters.put("gateEvaluations", countEvents(events, "GATE_EVALUATED"));
        counters.put("gateFailures", events.stream().filter(e -> e.type().equals("GATE_EVALUATED")
                && !"PASS".equals(e.data().get("verdict"))).count());
        counters.put("approvalsGranted", countEvents(events, "APPROVAL_GRANTED"));
        counters.put("approvalsRejected", countEvents(events, "APPROVAL_REJECTED"));

        List<TaskRecord> attempted = runs.stream().flatMap(r -> r.tasks().stream())
                .filter(t -> ATTEMPTED.contains(t.status())).toList();
        long succeeded = attempted.stream().filter(t -> t.status().satisfiesDependents()).count();
        double taskSuccessRate = attempted.isEmpty() ? 0 : round((double) succeeded / attempted.size());
        double runSuccessRate = runs.isEmpty() ? 0
                : round((double) runs.stream().filter(r -> r.status() == RunStatus.SUCCEEDED).count() / runs.size());

        Map<String, Double> stageLatency = runs.stream().flatMap(r -> r.tasks().stream())
                .filter(TaskRecord::executed)
                .collect(Collectors.groupingBy(TaskRecord::stage, TreeMap::new,
                        Collectors.averagingLong(TaskRecord::durationMillis)));
        stageLatency.replaceAll((k, v) -> round(v));

        List<Long> durations = runs.stream().map(RunReport::durationMillis).sorted().toList();
        Mttr mttr = mttr(events);
        return new MetricsReport(runs.size(), runSuccessRate, taskSuccessRate, counters, stageLatency,
                percentile(durations, 50), percentile(durations, 95), mttr.count(), mttr.meanMillis());
    }

    /**
     * Nearest-rank percentile.
     *
     * @param sortedValues values in ascending order
     * @param p            percentile in (0, 100]
     * @return the percentile value, or 0 for an empty list
     */
    public static long percentile(List<Long> sortedValues, int p) {
        if (sortedValues.isEmpty()) {
            return 0;
        }
        int rank = (int) Math.ceil(p / 100.0 * sortedValues.size());
        return sortedValues.get(Math.max(0, rank - 1));
    }

    /**
     * Writes metrics as pretty JSON.
     *
     * @param report metrics
     * @param file   target file
     * @throws IOException on write failure
     */
    public static void write(MetricsReport report, Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(file.toFile(), report);
    }

    private record Mttr(int count, Long meanMillis) {
    }

    private static Mttr mttr(List<AuditLog.Event> events) {
        Map<Object, Instant> opened = new HashMap<>();
        long total = 0;
        int count = 0;
        for (AuditLog.Event e : events) {
            Object id = e.data().get("incidentId");
            if (e.type().equals("INCIDENT_OPENED")) {
                opened.putIfAbsent(id, e.ts());
            } else if (e.type().equals("INCIDENT_RESOLVED") && opened.containsKey(id)) {
                total += Duration.between(opened.remove(id), e.ts()).toMillis();
                count++;
            }
        }
        return new Mttr(count, count == 0 ? null : total / count);
    }

    private static long countEvents(List<AuditLog.Event> events, String type) {
        return events.stream().filter(e -> e.type().equals(type)).count();
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
