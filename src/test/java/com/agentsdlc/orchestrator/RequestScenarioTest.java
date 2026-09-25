package com.agentsdlc.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentsdlc.orchestrator.approval.ScriptedApprovalProvider;
import com.agentsdlc.orchestrator.approval.ScriptedClarificationProvider;
import com.agentsdlc.orchestrator.llm.DeterministicLlmProvider;
import com.agentsdlc.orchestrator.llm.LlmProvider;
import com.agentsdlc.orchestrator.llm.LlmRequest;
import com.agentsdlc.orchestrator.scenario.RequestScenario;
import com.agentsdlc.orchestrator.scenario.ScenarioResult;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The "any requirement" pipeline driven by a scripted stand-in for a hosted
 * model. The fake answers the way a real model does: in code fences, with a
 * feature it named itself, so the path exercised is the real-model path.
 */
class RequestScenarioTest {

    private static final Path OUT = Path.of(System.getProperty("agentic.test.root", "target/test-working-tree"),
            "request-tests");
    private static final Path REPO = Path.of("").toAbsolutePath();

    /** Answers like a hosted model would for a slug-rules feature. */
    static final class FakeModel implements LlmProvider {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String name() {
            return "fake-hosted";
        }

        @Override
        public String complete(LlmRequest r) {
            calls.incrementAndGet();
            return switch (r.purpose()) {
                case "normalize" -> {
                    boolean vague = r.prompt().contains("better") && r.vars().get("clarifications").equals("{}");
                    yield "```json\n{\"title\":\"Slug rules\",\"kind\":\"FEATURE\",\"feature\":\"slug-rules\","
                            + "\"goals\":[\"validate custom aliases\"],"
                            + "\"acceptanceCriteria\":[\"3-32 chars\",\"lowercase letters, digits, hyphen\"],"
                            + "\"constraints\":[],\"ambiguityScore\":" + (vague ? "0.8" : "0.1") + ","
                            + "\"openQuestions\":" + (vague ? "{\"q1\":\"Which rules should aliases follow?\"}" : "{}")
                            + "}\n```";
                }
                case "design", "docs" -> "# Slug rules\n\nA pure validator.\n";
                case "code" -> r.vars().get("template").endsWith("/test") ? TEST : MAIN;
                default -> "";
            };
        }
    }

    static final String MAIN = """
            Here is the implementation:
            ```java
            package generated.slugrules;

            /** Validates custom alias slugs. */
            public final class SlugRules {
                private SlugRules() {
                }

                /** True when the slug is 3-32 chars of lowercase letters, digits or hyphens. */
                public static boolean isValid(String slug) {
                    return slug != null && slug.matches("[a-z0-9-]{3,32}");
                }
            }
            ```
            """;

    static final String TEST = """
            ```java
            package generated.slugrules;

            import static org.junit.jupiter.api.Assertions.assertFalse;
            import static org.junit.jupiter.api.Assertions.assertTrue;

            import org.junit.jupiter.api.Test;

            class SlugRulesTest {
                @Test
                void acceptsValidSlugs() {
                    assertTrue(SlugRules.isValid("my-link-1"));
                }

                @Test
                void rejectsTooShortOrLong() {
                    assertFalse(SlugRules.isValid("ab"));
                    assertFalse(SlugRules.isValid("a".repeat(33)));
                }

                @Test
                void rejectsUppercaseAndNull() {
                    assertFalse(SlugRules.isValid("MyLink"));
                    assertFalse(SlugRules.isValid(null));
                }
            }
            ```
            """;

    private static PrintStream quiet() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    private static ScriptedApprovalProvider approver() {
        return new ScriptedApprovalProvider("reviewer", Clock.systemUTC());
    }

    @Test
    void clearRequirementIsSpecifiedBuiltTestedAndReleased() {
        ScenarioResult r = RequestScenario.run("Add validation rules for custom alias slugs", OUT.resolve("clear"),
                REPO, new FakeModel(), approver(), new ScriptedClarificationProvider(Map.of()), quiet());
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.passed()).as(c.name() + " -> " + c.detail()).isTrue());
        assertThat(r.workDir().resolve("generated/src/main/java/generated/slugrules/SlugRules.java")).exists();
    }

    @Test
    void vagueRequirementAsksTheHumanOnceThenBuilds() {
        ScriptedClarificationProvider human = new ScriptedClarificationProvider(
                Map.of("q1", "3-32 lowercase letters, digits or hyphens"));
        ScenarioResult r = RequestScenario.run("Make aliases better", OUT.resolve("vague"), REPO, new FakeModel(),
                approver(), human, quiet());
        assertThat(r.success()).isTrue();
        assertThat(human.calls()).isEqualTo(1);
        assertThat(r.metrics().counters()).containsEntry("replans", 1L);
    }

    @Test
    void humanRejectionAtSignOffStopsTheReleaseSafely() {
        ScriptedApprovalProvider approvals = approver().reject("release", "final-signoff", "not this sprint");
        ScenarioResult r = RequestScenario.run("Add validation rules for custom alias slugs", OUT.resolve("reject"),
                REPO, new FakeModel(), approvals, new ScriptedClarificationProvider(Map.of()), quiet());
        assertThat(r.success()).isFalse();
        assertThat(r.metrics().counters()).containsEntry("approvalsRejected", 1L).containsEntry("rollbacks", 0L);
    }

    @Test
    void offlineModeRefusesToBuildWhatItCannotSpecify() {
        ScenarioResult r = RequestScenario.run("Add link previews with page titles", OUT.resolve("offline"), REPO,
                new DeterministicLlmProvider(), approver(), new ScriptedClarificationProvider(Map.of()), quiet());
        assertThat(r.success()).isFalse();
        assertThat(r.metrics().counters()).containsEntry("tasksBlocked", 1L).containsEntry("attempts", 2L);
    }
}
