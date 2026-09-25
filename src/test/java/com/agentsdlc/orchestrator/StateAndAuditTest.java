package com.agentsdlc.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentsdlc.orchestrator.audit.AuditLog;
import com.agentsdlc.orchestrator.state.StateStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StateAndAuditTest {

    @TempDir
    Path dir;

    @Test
    void namespaceHashIsContentBasedAndUnambiguous() {
        StateStore s = new StateStore();
        s.put("a", "x", "1");
        s.put("a", "y", "2");
        StateStore t = new StateStore();
        t.put("a", "y", "2");
        t.put("a", "x", "1");
        assertThat(s.hash("a")).isEqualTo(t.hash("a"));

        StateStore u = new StateStore();
        u.put("n", "ab", "c");
        StateStore v = new StateStore();
        v.put("n", "a", "bc");
        assertThat(u.hash("n")).isNotEqualTo(v.hash("n"));
    }

    @Test
    void snapshotRestoreAndConcurrentWritesAreSafe() {
        StateStore s = new StateStore();
        s.put("t", "k", "before");
        Map<String, String> snap = s.snapshot("t");
        s.put("t", "k", "after");
        s.put("t", "extra", "x");
        s.restore("t", snap);
        assertThat(s.snapshot("t")).isEqualTo(Map.of("k", "before"));

        IntStream.range(0, 1000).parallel().forEach(i -> s.put("p", "k" + i, "v"));
        assertThat(s.snapshot("p")).hasSize(1000);
    }

    @Test
    void auditLogIsAppendOnlyWithMonotonicSequenceAcrossReopen() throws Exception {
        Path file = dir.resolve("audit.jsonl");
        try (AuditLog log = new AuditLog(file, Clock.systemUTC())) {
            IntStream.range(0, 50).parallel().forEach(i -> log.record("r", "E", "t" + i, Map.of("z", i, "a", "x")));
        }
        try (AuditLog reopened = new AuditLog(file, Clock.systemUTC())) {
            reopened.record("r2", "LATER", null, Map.of());
        }
        List<AuditLog.Event> events = AuditLog.read(file);
        assertThat(events).hasSize(51);
        assertThat(events).extracting(AuditLog.Event::seq)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 51).mapToObj(i -> (long) i).toList());
        String first = Files.readAllLines(file).get(0);
        assertThat(first.indexOf("\"a\"")).isLessThan(first.indexOf("\"z\"")); // keys sorted, stable output
    }
}
