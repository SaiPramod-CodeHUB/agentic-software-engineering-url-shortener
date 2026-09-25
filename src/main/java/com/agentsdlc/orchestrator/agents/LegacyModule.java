package com.agentsdlc.orchestrator.agents;

import com.agentsdlc.orchestrator.tools.JavaToolchain;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Layout of, and build helper for, the brownfield "legacy" alias module that
 * the incident pipeline works on inside the scenario working directory.
 */
public final class LegacyModule {

    /** Main source of the registry, relative to the working directory. */
    public static final String REGISTRY = "legacy/src/main/java/legacy/alias/AliasRegistry.java";
    /** Test source root, relative to the working directory. */
    public static final String TEST_ROOT = "legacy/src/test/java/legacy/alias/";
    /** Stale-then-corrected documentation, relative to the working directory. */
    public static final String DOC = "legacy/docs/ALIASES.md";
    /** Regression test class name. */
    public static final String REGRESSION_TEST = "legacy.alias.AliasRegistryRaceTest";
    /** Edge-case test class name. */
    public static final String EDGE_TEST = "legacy.alias.AliasRegistryEdgeCaseTest";

    private final Path workDir;
    private final JavaToolchain toolchain = new JavaToolchain();

    /**
     * Creates the helper.
     *
     * @param workDir scenario working directory
     */
    public LegacyModule(Path workDir) {
        this.workDir = workDir;
    }

    /**
     * Compiles the registry and the given tests into a fresh output directory and runs the tests.
     *
     * @param testClasses fully-qualified test classes to run (their sources must exist)
     * @param outName     name of the output directory under {@code legacy/}
     * @return the test result
     * @throws IOException on file errors
     */
    public JavaToolchain.TestResult compileAndTest(List<String> testClasses, String outName) throws IOException {
        List<Path> sources = new ArrayList<>();
        sources.add(workDir.resolve(REGISTRY));
        for (String test : testClasses) {
            sources.add(workDir.resolve("legacy/src/test/java/" + AgentSupport.sourcePath(test)));
        }
        Path out = compile(sources, outName);
        return toolchain.runTests(out, testClasses);
    }

    /**
     * Compiles sources into {@code legacy/<outName>}, replacing previous output.
     *
     * @param sources sources to compile
     * @param outName output directory name
     * @return the output directory
     * @throws IOException on file errors
     */
    public Path compile(List<Path> sources, String outName) throws IOException {
        Path out = workDir.resolve("legacy/" + outName);
        TesterAgent.deleteRecursively(out);
        JavaToolchain.CompileResult result = toolchain.compile(sources, out);
        if (!result.success()) {
            throw new IllegalStateException("legacy module does not compile:\n" + result.diagnostics());
        }
        return out;
    }

    /**
     * Reads a file of the module.
     *
     * @param relative path relative to the working directory
     * @return file content
     * @throws IOException on read errors
     */
    public String read(String relative) throws IOException {
        return Files.readString(workDir.resolve(relative));
    }
}
