package com.agentsdlc.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentsdlc.orchestrator.core.Agent;
import com.agentsdlc.orchestrator.core.GraphValidationException;
import com.agentsdlc.orchestrator.core.RetryPolicy;
import com.agentsdlc.orchestrator.core.TaskGraph;
import com.agentsdlc.orchestrator.core.TaskSpec;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TaskGraphTest {

    private static final Agent NOOP = ctx -> { };

    private static TaskSpec task(String id, String... deps) {
        return TaskSpec.builder(id, NOOP).dependsOn(deps).build();
    }

    @Test
    void layersIntoDeterministicWavesWithParallelSiblings() {
        TaskGraph g = new TaskGraph(List.of(task("release", "test", "docs"), task("test", "impl"),
                task("docs", "impl"), task("impl", "design"), task("design")));
        assertThat(g.waves()).extracting(w -> w.stream().map(TaskSpec::id).toList())
                .containsExactly(List.of("design"), List.of("impl"), List.of("docs", "test"), List.of("release"));
    }

    @Test
    void rejectsDuplicateIdsUnknownDependenciesAndCyclesEagerly() {
        assertThatThrownBy(() -> new TaskGraph(List.of(task("a"), task("a"))))
                .isInstanceOf(GraphValidationException.class).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> new TaskGraph(List.of(task("a", "ghost"))))
                .isInstanceOf(GraphValidationException.class).hasMessageContaining("unknown task 'ghost'");
        assertThatThrownBy(() -> new TaskGraph(List.of(task("a", "c"), task("b", "a"), task("c", "b"), task("d"))))
                .isInstanceOf(GraphValidationException.class).hasMessageContaining("cycle among tasks [a, b, c]");
        assertThatThrownBy(() -> TaskSpec.builder("Bad_Id", NOOP).build())
                .isInstanceOf(GraphValidationException.class);
    }

    @Test
    void downstreamClosureIsTransitiveAndExcludesRoots() {
        TaskGraph g = new TaskGraph(List.of(task("a"), task("b", "a"), task("c", "b"), task("d", "a"), task("e")));
        assertThat(g.downstreamOf(Set.of("a"))).containsExactly("b", "c", "d");
        assertThat(g.downstreamOf(Set.of("e"))).isEmpty();
    }

    @Test
    void retryBackoffIsExponentialAndCapped() {
        RetryPolicy p = RetryPolicy.exponential(10, Duration.ofMillis(100));
        assertThat(p.backoffBefore(1)).isZero();
        assertThat(p.backoffBefore(2)).isEqualTo(Duration.ofMillis(100));
        assertThat(p.backoffBefore(4)).isEqualTo(Duration.ofMillis(400));
        assertThat(p.backoffBefore(30)).isEqualTo(RetryPolicy.MAX_BACKOFF);
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ZERO, 2)).isInstanceOf(IllegalArgumentException.class);
    }
}
