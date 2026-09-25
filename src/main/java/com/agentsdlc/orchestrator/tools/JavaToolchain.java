package com.agentsdlc.orchestrator.tools;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/**
 * Compiles generated Java sources and runs their JUnit 5 tests in-process.
 *
 * <p>In-process (javax.tools + JUnit Platform Launcher) rather than forking
 * {@code mvn} or {@code javac}: no network, no second JVM start-up per task,
 * structured results instead of parsing console output, and the same code
 * path works under Surefire, from the scenario scripts and in CI. Each run
 * uses a fresh class loader so repeated compilations of the same class name
 * (legacy vs. fixed code) never see each other.</p>
 */
public final class JavaToolchain {

    /**
     * Result of a compilation.
     *
     * @param success     whether javac reported no errors
     * @param diagnostics compiler output (empty on success)
     */
    public record CompileResult(boolean success, String diagnostics) {
    }

    /**
     * Result of a test run.
     *
     * @param run      tests started
     * @param passed   tests succeeded
     * @param failed   tests failed or aborted
     * @param failures one line per failure: test name and message
     */
    public record TestResult(long run, long passed, long failed, List<String> failures) {

        /**
         * Copies the failure list.
         *
         * @param run      tests started
         * @param passed   tests succeeded
         * @param failed   tests failed
         * @param failures failure descriptions
         */
        public TestResult {
            failures = List.copyOf(failures);
        }
    }

    /** Creates the toolchain; it is stateless. */
    public JavaToolchain() {
        // Stateless.
    }

    /**
     * The classpath generated code compiles against. Under Surefire the JVM
     * classpath is a single manifest-only booter jar, so Surefire's own
     * property is preferred; everywhere else the JVM classpath is correct.
     *
     * @return a platform-separated classpath
     */
    public static String hostClasspath() {
        String surefire = System.getProperty("surefire.test.class.path");
        return surefire != null && !surefire.isBlank() ? surefire : System.getProperty("java.class.path");
    }

    /**
     * Compiles sources.
     *
     * @param sources  {@code .java} files
     * @param outDir   directory for {@code .class} files (created if absent)
     * @return the compilation result
     */
    public CompileResult compile(List<Path> sources, Path outDir) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new CompileResult(false, "no system Java compiler: run on a JDK, not a JRE");
        }
        try {
            Files.createDirectories(outDir);
            StringWriter diagnostics = new StringWriter();
            try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
                List<String> options = List.of("-d", outDir.toString(), "-classpath", hostClasspath(),
                        "--release", "21", "-proc:none", "-Xlint:all", "-Werror");
                boolean ok = compiler.getTask(diagnostics, files, null, options, null,
                        files.getJavaFileObjectsFromPaths(sources)).call();
                return new CompileResult(ok, diagnostics.toString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Runs JUnit 5 test classes from a compiled output directory.
     *
     * @param classesDir directory containing the compiled classes
     * @param testClasses fully-qualified test class names
     * @return the test result
     */
    public TestResult runTests(Path classesDir, List<String> testClasses) {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {toUrl(classesDir)}, getClass().getClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            List<Class<?>> classes = new ArrayList<>();
            for (String name : testClasses) {
                classes.add(loader.loadClass(name));
            }
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(classes.stream().map(c -> selectClass(c)).toList())
                    .build();
            Launcher launcher = LauncherFactory.create();
            SummaryGeneratingListener listener = new SummaryGeneratingListener();
            launcher.execute(request, listener);
            TestExecutionSummary summary = listener.getSummary();
            List<String> failures = summary.getFailures().stream()
                    .map(f -> f.getTestIdentifier().getDisplayName() + ": " + f.getException().getMessage())
                    .toList();
            return new TestResult(summary.getTestsStartedCount(), summary.getTestsSucceededCount(),
                    summary.getTestsFailedCount() + summary.getTestsAbortedCount(), failures);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("compiled test class not found: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /**
     * Lists {@code .java} files under a directory.
     *
     * @param dir root directory
     * @return sorted source paths
     */
    public static List<Path> javaSources(Path dir) {
        try (var walk = Files.walk(dir)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static URL toUrl(Path dir) {
        try {
            return dir.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(dir.toString(), e);
        }
    }

    /**
     * Joins paths into a classpath string.
     *
     * @param paths entries
     * @return the classpath
     */
    public static String join(List<Path> paths) {
        return String.join(File.pathSeparator, paths.stream().map(Path::toString).toList());
    }
}
