package com.agentsdlc.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentsdlc.orchestrator.audit.AuditLog;
import com.agentsdlc.orchestrator.core.Tags;
import com.agentsdlc.orchestrator.core.TaskSpec;
import com.agentsdlc.orchestrator.gate.ChangeControlGate;
import com.agentsdlc.orchestrator.gate.ComplianceGate;
import com.agentsdlc.orchestrator.gate.GateContext;
import com.agentsdlc.orchestrator.gate.GateResult;
import com.agentsdlc.orchestrator.gate.ImpactAnalysisGate;
import com.agentsdlc.orchestrator.gate.SecretsScanGate;
import com.agentsdlc.orchestrator.gate.SpecReadyGate;
import com.agentsdlc.orchestrator.gate.TestsMustPassGate;
import com.agentsdlc.orchestrator.state.StateStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatesTest {

    @TempDir
    Path dir;

    private final StateStore state = new StateStore();

    private GateContext ctx(String taskId, List<Path> artifacts, String... tags) {
        TaskSpec task = TaskSpec.builder(taskId, c -> { }).tags(tags).build();
        return new GateContext("run-1", task, state, dir, artifacts, new AuditLog(dir.resolve("a.jsonl"),
                Clock.systemUTC()));
    }

    private Path file(String name, String content) throws Exception {
        return Files.writeString(dir.resolve(name), content);
    }

    @Test
    void secretsScanFlagsCredentialsButNotOrdinaryCode() throws Exception {
        // Fixtures are assembled at runtime so this source file itself never matches the rules.
        Path aws = file("a.txt", "key=" + "AKIA" + "ABCDEFGHIJKLMNOP");
        Path pem = file("b.txt", "-----BEGIN " + "RSA PRIVATE KEY-----");
        Path generic = file("c.txt", "password = \"" + "hunter2hunter2hunter2" + "\"");
        Path clean = file("d.java", "String password = System.getenv(\"DB_PASSWORD\");");
        SecretsScanGate gate = new SecretsScanGate();
        for (Path leaked : List.of(aws, pem, generic)) {
            assertThat(gate.evaluate(ctx("t", List.of(leaked))).verdict()).isEqualTo(GateResult.Verdict.BLOCK);
        }
        GateResult ok = gate.evaluate(ctx("t", List.of(clean)));
        assertThat(ok.passed()).isTrue();
    }

    @Test
    void complianceFlagsEmailPhoneAndRawIpWithoutEchoingThem() throws Exception {
        Path pii = file("log.txt", "user " + "jane" + "@" + "example.com" + " from " + "203.0.113" + ".7\ncall 512-555-0100");
        GateResult r = new ComplianceGate().evaluate(ctx("t", List.of(pii)));
        assertThat(r.verdict()).isEqualTo(GateResult.Verdict.BLOCK);
        assertThat(r.reason()).contains("email", "ipv4", "phone").doesNotContain("203.0.113", "jane");
        Path clean = file("ok.txt", "version 2026-01-01, visitor hash 9f86d081884c7d65, 213 bytes");
        assertThat(new ComplianceGate().evaluate(ctx("t", List.of(clean))).passed()).isTrue();
    }

    @Test
    void changeControlRequiresAnApprovedChangeRecord() {
        ChangeControlGate gate = new ChangeControlGate("change-record");
        assertThat(gate.appliesTo(TaskSpec.builder("release", c -> { }).tags(Tags.RELEASE).build())).isTrue();
        assertThat(gate.evaluate(ctx("release", List.of())).verdict()).isEqualTo(GateResult.Verdict.BLOCK);
        state.put("change-record", "status", "PENDING");
        assertThat(gate.evaluate(ctx("release", List.of())).passed()).isFalse();
        state.put("change-record", "status", "APPROVED");
        assertThat(gate.evaluate(ctx("release", List.of())).passed()).isTrue();
    }

    @Test
    void testsMustPassRejectsZeroTestsAndFailures() {
        TestsMustPassGate gate = new TestsMustPassGate();
        assertThat(gate.evaluate(ctx("test", List.of())).reason()).isEqualTo("no tests were executed");
        state.put("test", "tests.run", "4");
        state.put("test", "tests.failed", "1");
        assertThat(gate.evaluate(ctx("test", List.of())).passed()).isFalse();
        state.put("test", "tests.failed", "0");
        assertThat(gate.evaluate(ctx("test", List.of())).passed()).isTrue();
    }

    @Test
    void impactAnalysisAndSpecReadyGatesCheckTheirPreconditions() throws Exception {
        ImpactAnalysisGate impact = new ImpactAnalysisGate();
        assertThat(impact.evaluate(ctx("fix", List.of())).reason()).contains("missing");
        file(ImpactAnalysisGate.REPORT, "## Affected classes\n## Endpoints\n");
        assertThat(impact.evaluate(ctx("fix", List.of())).reason()).contains("## Tables", "## Call paths");
        file(ImpactAnalysisGate.REPORT, String.join("\n", ImpactAnalysisGate.SECTIONS));
        assertThat(impact.evaluate(ctx("fix", List.of())).passed()).isTrue();

        SpecReadyGate spec = new SpecReadyGate("requirements");
        state.put("requirements", "spec.status", "DRAFT");
        assertThat(spec.evaluate(ctx("design", List.of())).passed()).isFalse();
        state.put("requirements", "spec.status", "READY");
        assertThat(spec.evaluate(ctx("design", List.of())).passed()).isTrue();
    }
}
