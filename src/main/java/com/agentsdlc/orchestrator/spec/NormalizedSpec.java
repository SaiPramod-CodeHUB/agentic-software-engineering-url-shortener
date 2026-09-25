package com.agentsdlc.orchestrator.spec;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A natural-language request turned into a structured, versioned spec.
 *
 * @param version            increments each time the spec is re-normalised
 * @param request            the original request text
 * @param title              short title
 * @param kind               {@code FEATURE} or {@code INCIDENT}; selects the pipeline template
 * @param feature            feature key used by downstream agents
 * @param goals              what the requester wants
 * @param acceptanceCriteria testable conditions for "done"
 * @param constraints        non-functional constraints
 * @param ambiguityScore     0 (precise) to 1 (vague)
 * @param openQuestions      clarifying questions by id; empty when none
 * @param clarifications     human answers by question id
 * @param status             {@code DRAFT} (do not build) or {@code READY}
 */
public record NormalizedSpec(
        int version,
        String request,
        String title,
        String kind,
        String feature,
        List<String> goals,
        List<String> acceptanceCriteria,
        List<String> constraints,
        double ambiguityScore,
        Map<String, String> openQuestions,
        Map<String, String> clarifications,
        Status status) {

    /** Whether the spec may be built. */
    public enum Status {
        /** Too ambiguous to build; clarifying questions must be answered first. */
        DRAFT,
        /** Precise enough to design and implement. */
        READY
    }

    /**
     * Copies collections defensively.
     *
     * @param version            spec version
     * @param request            original request
     * @param title              title
     * @param kind               FEATURE or INCIDENT
     * @param feature            feature key
     * @param goals              goals
     * @param acceptanceCriteria acceptance criteria
     * @param constraints        constraints
     * @param ambiguityScore     ambiguity score
     * @param openQuestions      open questions
     * @param clarifications     human answers
     * @param status             build status
     */
    public NormalizedSpec {
        goals = List.copyOf(goals);
        acceptanceCriteria = List.copyOf(acceptanceCriteria);
        constraints = List.copyOf(constraints);
        openQuestions = Map.copyOf(openQuestions);
        clarifications = Map.copyOf(clarifications);
    }

    /**
     * Renders the spec as Markdown. Drafts carry a prominent
     * {@code DRAFT (DO NOT BUILD)} banner so a human skimming artifacts
     * cannot mistake one for an approved spec.
     *
     * @return Markdown document
     */
    public String toMarkdown() {
        StringBuilder md = new StringBuilder();
        if (status == Status.DRAFT) {
            md.append("# DRAFT (DO NOT BUILD) — ").append(title).append("\n\n");
        } else {
            md.append("# Spec v").append(version).append(" — ").append(title).append("\n\n");
        }
        md.append("- Status: ").append(status).append("\n");
        md.append("- Kind: ").append(kind).append(" (feature: ").append(feature).append(")\n");
        md.append("- Ambiguity score: ").append(ambiguityScore).append("\n");
        md.append("- Original request: \"").append(request).append("\"\n\n");
        section(md, "Goals", goals);
        section(md, "Acceptance criteria", acceptanceCriteria);
        section(md, "Constraints", constraints);
        if (!openQuestions.isEmpty()) {
            md.append("## Clarifying questions\n\n");
            new TreeMap<>(openQuestions).forEach((id, q) -> md.append("- **").append(id).append("**: ")
                    .append(q).append("\n"));
            md.append("\n");
        }
        if (!clarifications.isEmpty()) {
            md.append("## Human clarifications\n\n");
            new TreeMap<>(clarifications).forEach((id, a) -> md.append("- **").append(id).append("**: ")
                    .append(a).append("\n"));
            md.append("\n");
        }
        return md.toString();
    }

    private static void section(StringBuilder md, String heading, List<String> items) {
        md.append("## ").append(heading).append("\n\n");
        if (items.isEmpty()) {
            md.append("- (none — needs clarification)\n");
        }
        items.forEach(i -> md.append("- ").append(i).append("\n"));
        md.append("\n");
    }
}
