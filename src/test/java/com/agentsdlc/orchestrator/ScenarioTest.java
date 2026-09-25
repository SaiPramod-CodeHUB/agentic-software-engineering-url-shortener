package com.agentsdlc.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentsdlc.orchestrator.scenario.AmbiguousScenario;
import com.agentsdlc.orchestrator.scenario.BrownfieldScenario;
import com.agentsdlc.orchestrator.scenario.GreenfieldScenario;
import com.agentsdlc.orchestrator.scenario.ScenarioEnvironment;
import com.agentsdlc.orchestrator.scenario.ScenarioResult;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Runs the three end-to-end scenarios exactly as the shell scripts do, into target/. */
class ScenarioTest {

    private static final Path OUT = Path.of(System.getProperty("agentic.test.root", "target/test-working-tree"));
    private static final Path REPO = Path.of("").toAbsolutePath();

    private static void assertSucceeded(ScenarioResult result) {
        assertThat(result.checks())
                .allSatisfy(c -> assertThat(c.passed()).as(c.name() + " -> " + c.detail()).isTrue());
        assertThat(result.success()).isTrue();
        assertThat(result.workDir().resolve("audit.jsonl")).exists();
        assertThat(result.workDir().resolve("metrics.json")).exists();
        assertThat(result.metrics().endToEndLatencyP95Ms()).isGreaterThanOrEqualTo(result.metrics().endToEndLatencyP50Ms());
    }

    private static PrintStream quiet() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    @Test
    void greenfieldBuildsTestsAndReleasesTheQrFeature() {
        assertSucceeded(GreenfieldScenario.run(OUT, REPO, quiet()));
    }

    @Test
    void brownfieldFixesTheAliasRaceAndDemonstratesRollback() throws Exception {
        ScenarioResult result = BrownfieldScenario.run(OUT, REPO, quiet());
        assertSucceeded(result);
        assertThat(result.metrics().mttrMs()).isNotNull();
        assertThat(Files.readString(result.workDir().resolve("IMPACT_ANALYSIS.md"))).contains("## Call paths");
    }

    @Test
    void ambiguousRequestIsClarifiedOnceThenReplannedAndBuilt() {
        ScenarioResult result = AmbiguousScenario.run(OUT, REPO, quiet());
        assertSucceeded(result);
        assertThat(result.metrics().counters()).containsEntry("replans", 1L);
        assertThat(result.checks()).extracting(ScenarioEnvironment.Check::name)
                .contains("human asked exactly once; preserved work not redone");
    }
}
