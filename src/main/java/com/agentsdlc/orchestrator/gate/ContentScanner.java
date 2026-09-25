package com.agentsdlc.orchestrator.gate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Line-oriented pattern scanner shared by the secrets and compliance gates.
 * Findings report the rule, file and line — never the matched value, so the
 * audit log does not become a second copy of the leaked secret or PII.
 */
public final class ContentScanner {

    /** Credential patterns. */
    public static final Map<String, Pattern> SECRET_RULES = Map.of(
            "aws-access-key", Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            "private-key", Pattern.compile("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"),
            "github-token", Pattern.compile("\\bghp_[A-Za-z0-9]{36}\\b"),
            "generic-credential", Pattern.compile(
                    "(?i)\\b(api[_-]?key|secret|password|passwd|token)\\s*[:=]\\s*[\"'][^\"'\\s]{12,}[\"']"));

    /** Personal-data patterns: emails, phone numbers, raw IP addresses. */
    public static final Map<String, Pattern> PII_RULES = Map.of(
            "email", Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"),
            "phone", Pattern.compile("(?<![\\d-])\\(?\\d{3}\\)?[ .-]\\d{3}[ .-]\\d{4}(?![\\d-])"),
            "ipv4", Pattern.compile("\\b((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\b"));

    /**
     * One match.
     *
     * @param rule rule name
     * @param file file name
     * @param line 1-based line number
     */
    public record Finding(String rule, String file, int line) {
        @Override
        public String toString() {
            return rule + " at " + file + ":" + line;
        }
    }

    private ContentScanner() {
    }

    /**
     * Scans files.
     *
     * @param files files to scan
     * @param rules rules to apply
     * @return all findings, in file and line order
     */
    public static List<Finding> scan(List<Path> files, Map<String, Pattern> rules) {
        List<Finding> findings = new ArrayList<>();
        for (Path file : files) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot scan " + file, e);
            }
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                for (var rule : new TreeMap<>(rules).entrySet()) {
                    if (rule.getValue().matcher(line).find()) {
                        findings.add(new Finding(rule.getKey(), file.getFileName().toString(), i + 1));
                    }
                }
            }
        }
        return findings;
    }
}
