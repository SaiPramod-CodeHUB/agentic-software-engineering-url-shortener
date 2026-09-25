package com.agentsdlc.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentsdlc.orchestrator.audit.AuditLog;
import com.agentsdlc.orchestrator.core.TaskStatus;
import com.agentsdlc.orchestrator.engine.RunReport;
import com.agentsdlc.orchestrator.engine.RunStatus;
import com.agentsdlc.orchestrator.engine.TaskRecord;
import com.agentsdlc.orchestrator.metrics.Metrics;
import com.agentsdlc.orchestrator.metrics.MetricsReport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetricsTest {

    private static AuditLog.Event event(long seq, String ts, String type, Map<String, Object> data) {
        return new AuditLog.Event(seq, Instant.parse(ts), "r", type, null, data);
    }

    private static RunReport run(RunStatus status, long millis, TaskRecord... tasks) {
        return new RunReport("r", "p", status, Instant.EPOCH, millis, List.of(tasks), null);
    }

    @Test
    void percentileUsesNearestRank() {
        List<Long> v = List.of(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L);
        assertThat(Metrics.percentile(v, 50)).isEqualTo(50);
        assertThat(Metrics.percentile(v, 95)).isEqualTo(100);
        assertThat(Metrics.percentile(List.of(7L), 95)).isEqualTo(7);
        assertThat(Metrics.percentile(List.of(), 50)).isZero();
    }

    @Test
    void mttrPairsIncidentsByIdAndRatesCountOnlyAttemptedTasks() {
        List<AuditLog.Event> events = List.of(
                event(1, "2026-01-01T00:00:00Z", "INCIDENT_OPENED", Map.of("incidentId", "A")),
                event(2, "2026-01-01T00:00:10Z", "INCIDENT_OPENED", Map.of("incidentId", "B")),
                event(3, "2026-01-01T00:01:00Z", "INCIDENT_RESOLVED", Map.of("incidentId", "A")),
                event(4, "2026-01-01T00:02:10Z", "INCIDENT_RESOLVED", Map.of("incidentId", "B")),
                event(5, "2026-01-01T00:03:00Z", "TASK_RETRY", Map.of()),
                event(6, "2026-01-01T00:03:00Z", "ROLLBACK_STEP", Map.of("result", "compensated")),
                event(7, "2026-01-01T00:03:00Z", "ROLLBACK_STEP", Map.of("result", "no-compensation-needed")));
        MetricsReport m = Metrics.compute(List.of(
                run(RunStatus.ROLLED_BACK, 300,
                        new TaskRecord("a", "build", TaskStatus.COMPENSATED, 1, 100, null, null),
                        new TaskRecord("b", "deploy", TaskStatus.FAILED, 3, 200, "x", null),
                        new TaskRecord("c", "deploy", TaskStatus.SKIPPED, 0, 0, "x", null)),
                run(RunStatus.SUCCEEDED, 100,
                        new TaskRecord("a", "build", TaskStatus.SUCCEEDED, 1, 50, null, "h"),
                        new TaskRecord("b", "deploy", TaskStatus.SUCCEEDED, 1, 40, null, "h"))), events);
        assertThat(m.mttrMs()).isEqualTo(90_000L);
        assertThat(m.incidentsResolved()).isEqualTo(2);
        assertThat(m.counters()).containsEntry("retries", 1L).containsEntry("rollbacks", 1L);
        assertThat(m.taskSuccessRate()).isEqualTo(0.5);
        assertThat(m.runSuccessRate()).isEqualTo(0.5);
        assertThat(m.stageLatencyMeanMs()).containsEntry("build", 75.0).containsEntry("deploy", 120.0);
        assertThat(m.endToEndLatencyP50Ms()).isEqualTo(100);
        assertThat(m.endToEndLatencyP95Ms()).isEqualTo(300);
    }
}
