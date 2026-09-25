package com.agentsdlc.orchestrator.agents;

import com.agentsdlc.orchestrator.core.Agent;
import com.agentsdlc.orchestrator.core.TaskContext;
import com.agentsdlc.orchestrator.llm.LlmOutput;
import com.agentsdlc.orchestrator.llm.LlmRequest;
import com.agentsdlc.orchestrator.tools.JavaToolchain;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Writes unit tests for the implemented class, compiles production and test
 * sources together, and runs the tests in-process with the JUnit Platform.
 * Real compilation and real test execution: a broken implementation fails
 * this task, and the tests-must-pass gate refuses a run with zero tests.
 *
 * <p>Outputs: {@code tests.run}, {@code tests.passed}, {@code tests.failed},
 * {@code test.class}.</p>
 */
public final class TesterAgent implements Agent {

    /** Root of generated tests relative to the working directory. */
    public static final String TEST_ROOT = "generated/src/test/java/";

    private final String specTask;
    private final String implementTask;
    private final JavaToolchain toolchain = new JavaToolchain();

    /**
     * Creates the agent.
     *
     * @param specTask      id of the task that published the spec
     * @param implementTask id of the task that wrote the production source
     */
    public TesterAgent(String specTask, String implementTask) {
        this.specTask = specTask;
        this.implementTask = implementTask;
    }

    @Override
    public void execute(TaskContext ctx) throws IOException {
        String feature = ctx.require(specTask, "spec.feature");
        Path mainFile = ctx.workDir().resolve(ctx.require(implementTask, "source.path"));
        String prompt = ctx.require(specTask, "spec.md") + "\n\nImplementation under test:\n"
                + Files.readString(mainFile)
                + ctx.lastFailure().map(f -> "\n\nThe previous test attempt failed: " + f
                        + "\nWrite tests that match the implementation's actual, specified behaviour.").orElse("");
        String testSource = LlmOutput.stripFences(ctx.llm().complete(new LlmRequest("code", "Write JUnit 5 tests.",
                prompt, Map.of("feature", feature, "template", feature + "/test"))));
        String testClass = AgentSupport.qualifiedClassName(testSource);
        Path testFile = ctx.writeArtifact(TEST_ROOT + AgentSupport.sourcePath(testClass), testSource);

        Path classes = ctx.workDir().resolve("generated/classes");
        deleteRecursively(classes);
        JavaToolchain.CompileResult compiled = toolchain.compile(List.of(mainFile, testFile), classes);
        if (!compiled.success()) {
            throw new IllegalStateException("compilation failed:\n" + compiled.diagnostics());
        }
        JavaToolchain.TestResult result = toolchain.runTests(classes, List.of(testClass));
        ctx.put("tests.run", Long.toString(result.run()));
        ctx.put("tests.passed", Long.toString(result.passed()));
        ctx.put("tests.failed", Long.toString(result.failed()));
        ctx.put("test.class", testClass);
        ctx.writeArtifact("TEST_REPORT.md", "# Test report\n\n- Class: `" + testClass + "`\n- Run: " + result.run()
                + "\n- Passed: " + result.passed() + "\n- Failed: " + result.failed() + "\n"
                + result.failures().stream().map(f -> "  - " + f + "\n").reduce("", String::concat));
        ctx.decide("ran " + result.run() + " generated test(s)", "compiled and executed in-process for real evidence",
                Map.of("failed", Long.toString(result.failed())));
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
