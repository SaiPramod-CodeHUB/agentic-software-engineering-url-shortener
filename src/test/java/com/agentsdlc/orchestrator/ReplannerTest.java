package com.agentsdlc.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentsdlc.orchestrator.core.TaskGraph;
import com.agentsdlc.orchestrator.core.TaskSpec;
import com.agentsdlc.orchestrator.replan.Replanner;
import com.agentsdlc.orchestrator.state.StateStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplannerTest {

    @TempDir
    Path dir;

    @Test
    void invalidatesOnlyTheDownstreamClosureOfChangedOutputsAndAuditsIt() {
        Harness h = new Harness(dir);
        TaskGraph g = new TaskGraph(List.of(
                TaskSpec.builder("spec", ctx -> ctx.put("v", "1")).build(),
                TaskSpec.builder("inventory", ctx -> ctx.put("n", "42")).build(),
                TaskSpec.builder("design", ctx -> ctx.put("d", "x")).dependsOn("spec", "inventory").build(),
                TaskSpec.builder("build", ctx -> ctx.put("b", "y")).dependsOn("design").build()));
        h.orchestrator.run("p", g);
        h.state.put(StateStore.HUMAN_NS, "answers", "kept");
        Replanner replanner = new Replanner(g, h.state);
        Map<String, String> before = replanner.fingerprint();

        h.state.put("spec", "v", "2"); // upstream output changes
        Replanner.Plan plan = replanner.replan(before, h.audit, "p-run-1");

        assertThat(plan.changed()).containsExactly("spec");
        assertThat(plan.invalidated()).containsExactly("build", "design");
        assertThat(plan.preserved()).containsExactly("inventory");
        assertThat(h.state.snapshot("design")).isEmpty();
        assertThat(h.state.get("inventory", "n")).contains("42");
        assertThat(h.state.get(StateStore.HUMAN_NS, "answers")).contains("kept");
        assertThat(h.events("REPLAN", null)).isEqualTo(1);
    }

    @Test
    void noChangeMeansNothingIsInvalidated() {
        Harness h = new Harness(dir);
        TaskGraph g = new TaskGraph(List.of(TaskSpec.builder("a", ctx -> ctx.put("v", "1")).build(),
                TaskSpec.builder("b", ctx -> ctx.put("v", "2")).dependsOn("a").build()));
        h.orchestrator.run("p", g);
        Replanner r = new Replanner(g, h.state);
        Replanner.Plan plan = r.replan(r.fingerprint(), h.audit, "p-run-1");
        assertThat(plan.changed()).isEmpty();
        assertThat(plan.invalidated()).isEmpty();
        assertThat(plan.preserved()).isEqualTo(Set.of("a", "b"));
    }
}
