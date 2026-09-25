package com.agentsdlc.orchestrator.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A lightweight static index of a Java codebase used for brownfield impact
 * analysis: which classes mention a concept, which HTTP endpoints and tables
 * they own, and how they reference each other.
 *
 * <p>Deliberately regex-based rather than a full parser: it has no
 * dependencies, runs in milliseconds and is transparent about its
 * heuristics. The trade-off (it can over-report, e.g. a name in a comment) is
 * the safe direction for impact analysis — a human prunes false positives,
 * while a missed dependency is the dangerous error.</p>
 */
public final class SourceIndex {

    /**
     * One indexed source file.
     *
     * @param path       path relative to the repository root
     * @param simpleName class name
     * @param content    file content
     */
    public record SourceFile(String path, String simpleName, String content) {
    }

    private static final Pattern ENDPOINT =
            Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping\\(\\s*\"([^\"]*)\"");
    private static final Pattern TABLE = Pattern.compile("@Table\\(\\s*name\\s*=\\s*\"([^\"]+)\"");

    private final Map<String, SourceFile> byName;

    private SourceIndex(Map<String, SourceFile> byName) {
        this.byName = byName;
    }

    /**
     * Indexes every {@code .java} file under a source root.
     *
     * @param repoRoot   repository root (paths are reported relative to it)
     * @param sourceRoot source directory relative to the root, e.g. {@code src/main/java}
     * @return the index (empty when the directory does not exist)
     */
    public static SourceIndex scan(Path repoRoot, String sourceRoot) {
        Map<String, SourceFile> files = new TreeMap<>();
        Path dir = repoRoot.resolve(sourceRoot);
        if (Files.isDirectory(dir)) {
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                    String name = p.getFileName().toString().replace(".java", "");
                    files.put(name, new SourceFile(repoRoot.relativize(p).toString().replace('\\', '/'), name,
                            Files.readString(p)));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new SourceIndex(files);
    }

    /**
     * Number of indexed files.
     *
     * @return file count
     */
    public int size() {
        return byName.size();
    }

    /**
     * Files whose content mentions any keyword (case-insensitive).
     *
     * @param keywords concepts to look for
     * @return matching class names, sorted
     */
    public Set<String> mentioning(List<String> keywords) {
        Set<String> result = new TreeSet<>();
        byName.forEach((name, f) -> {
            String lower = f.content().toLowerCase(Locale.ROOT);
            if (keywords.stream().anyMatch(k -> lower.contains(k.toLowerCase(Locale.ROOT)))) {
                result.add(name);
            }
        });
        return result;
    }

    /**
     * Classes referenced from the given classes (one hop), excluding themselves.
     *
     * @param classes starting classes
     * @return referenced indexed classes, sorted
     */
    public Set<String> referencedBy(Set<String> classes) {
        Set<String> result = new TreeSet<>();
        for (String c : classes) {
            result.addAll(references(c));
        }
        result.removeAll(classes);
        return result;
    }

    /**
     * Indexed classes whose simple name appears as a word in a class's source.
     *
     * @param className referencing class
     * @return referenced classes, sorted
     */
    public Set<String> references(String className) {
        SourceFile file = byName.get(className);
        Set<String> result = new TreeSet<>();
        if (file == null) {
            return result;
        }
        for (String other : byName.keySet()) {
            if (!other.equals(className) && Pattern.compile("\\b" + other + "\\b").matcher(file.content()).find()) {
                result.add(other);
            }
        }
        return result;
    }

    /**
     * HTTP endpoints declared in a class, e.g. {@code POST /shorten}.
     *
     * @param className class name
     * @return endpoints in declaration order
     */
    public List<String> endpoints(String className) {
        return matches(className, ENDPOINT, m -> m.group(1).toUpperCase(Locale.ROOT) + " " + m.group(2));
    }

    /**
     * Database tables mapped by a class via {@code @Table(name = ...)}.
     *
     * @param className class name
     * @return table names
     */
    public List<String> tables(String className) {
        return matches(className, TABLE, m -> m.group(1));
    }

    /**
     * Relative path of a class's source file.
     *
     * @param className class name
     * @return the path, or {@code "?"} if unknown
     */
    public String pathOf(String className) {
        SourceFile f = byName.get(className);
        return f == null ? "?" : f.path();
    }

    /**
     * Enumerates reference paths starting at entry-point classes, restricted
     * to the given set of classes (depth-first, cycle-safe).
     *
     * @param entryPoints classes to start from (e.g. controllers)
     * @param within      classes allowed on a path
     * @return paths such as {@code A -> B -> C}
     */
    public List<String> callPaths(Set<String> entryPoints, Set<String> within) {
        List<String> paths = new ArrayList<>();
        for (String entry : entryPoints) {
            walk(entry, within, new LinkedHashSet<>(List.of(entry)), paths);
        }
        return paths;
    }

    private void walk(String current, Set<String> within, LinkedHashSet<String> path, List<String> out) {
        List<String> next = references(current).stream().filter(within::contains).filter(n -> !path.contains(n)).toList();
        if (next.isEmpty()) {
            if (path.size() > 1) {
                out.add(String.join(" -> ", path));
            }
            return;
        }
        for (String n : next) {
            path.add(n);
            walk(n, within, path, out);
            path.remove(n);
        }
    }

    private List<String> matches(String className, Pattern pattern, Function<Matcher, String> f) {
        SourceFile file = byName.get(className);
        List<String> result = new ArrayList<>();
        if (file != null) {
            Matcher m = pattern.matcher(file.content());
            while (m.find()) {
                result.add(f.apply(m));
            }
        }
        return result;
    }
}
