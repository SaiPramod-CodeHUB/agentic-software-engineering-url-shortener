package com.agentsdlc.orchestrator.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Declarative description of one node in the task DAG.
 *
 * @param id              unique task id; also the task's state namespace
 * @param stage           SDLC stage used for per-stage latency metrics
 * @param dependsOn       ids of tasks that must succeed first
 * @param risk            risk tier consulted by gates
 * @param destructive     whether the task deletes or migrates data/infrastructure
 * @param retry           retry policy for the primary agent
 * @param agent           the primary agent
 * @param fallback        agent tried once after retries are exhausted, or {@code null}
 * @param compensation    undo action used during rollback, or {@code null}
 * @param requiredOutputs output contract: keys the task must write to its namespace
 * @param tags            policy tags (see {@link Tags})
 */
public record TaskSpec(
        String id,
        String stage,
        Set<String> dependsOn,
        RiskTier risk,
        boolean destructive,
        RetryPolicy retry,
        Agent agent,
        Agent fallback,
        Compensation compensation,
        List<String> requiredOutputs,
        Set<String> tags) {

    /**
     * Validates and defensively copies the collections.
     *
     * @param id              unique task id
     * @param stage           SDLC stage
     * @param dependsOn       dependency ids
     * @param risk            risk tier
     * @param destructive     destructive flag
     * @param retry           retry policy
     * @param agent           primary agent
     * @param fallback        optional fallback agent
     * @param compensation    optional compensation
     * @param requiredOutputs output contract
     * @param tags            policy tags
     */
    public TaskSpec {
        // Kebab-case only: namespaces starting with '_' are reserved for the engine.
        if (id == null || !id.matches("[a-z0-9][a-z0-9-]*")) {
            throw new GraphValidationException("task id must be kebab-case: " + id);
        }
        Objects.requireNonNull(agent, "agent");
        stage = stage == null ? id : stage;
        dependsOn = Set.copyOf(dependsOn);
        risk = risk == null ? RiskTier.LOW : risk;
        retry = retry == null ? RetryPolicy.none() : retry;
        requiredOutputs = List.copyOf(requiredOutputs);
        tags = Set.copyOf(tags);
    }

    /**
     * Whether the task carries a tag.
     *
     * @param tag the tag
     * @return {@code true} if present
     */
    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }

    /**
     * Starts a builder.
     *
     * @param id    task id
     * @param agent primary agent
     * @return a builder with safe defaults (LOW risk, no retries)
     */
    public static Builder builder(String id, Agent agent) {
        return new Builder(id, agent);
    }

    /** Fluent builder; the record has too many fields for readable positional construction. */
    public static final class Builder {
        private final String id;
        private final Agent agent;
        private String stage;
        private final Set<String> dependsOn = new LinkedHashSet<>();
        private RiskTier risk = RiskTier.LOW;
        private boolean destructive;
        private RetryPolicy retry = RetryPolicy.none();
        private Agent fallback;
        private Compensation compensation;
        private final List<String> requiredOutputs = new ArrayList<>();
        private final Set<String> tags = new LinkedHashSet<>();

        private Builder(String id, Agent agent) {
            this.id = id;
            this.agent = agent;
        }

        /**
         * Sets the SDLC stage (defaults to the id).
         *
         * @param value stage name
         * @return this builder
         */
        public Builder stage(String value) {
            this.stage = value;
            return this;
        }

        /**
         * Adds dependencies.
         *
         * @param ids upstream task ids
         * @return this builder
         */
        public Builder dependsOn(String... ids) {
            this.dependsOn.addAll(List.of(ids));
            return this;
        }

        /**
         * Sets the risk tier.
         *
         * @param value risk tier
         * @return this builder
         */
        public Builder risk(RiskTier value) {
            this.risk = value;
            return this;
        }

        /**
         * Marks the task destructive.
         *
         * @return this builder
         */
        public Builder destructive() {
            this.destructive = true;
            return this;
        }

        /**
         * Sets the retry policy.
         *
         * @param value retry policy
         * @return this builder
         */
        public Builder retry(RetryPolicy value) {
            this.retry = value;
            return this;
        }

        /**
         * Sets the fallback agent.
         *
         * @param value fallback agent
         * @return this builder
         */
        public Builder fallback(Agent value) {
            this.fallback = value;
            return this;
        }

        /**
         * Sets the compensation action.
         *
         * @param value compensation
         * @return this builder
         */
        public Builder compensation(Compensation value) {
            this.compensation = value;
            return this;
        }

        /**
         * Adds keys to the output contract.
         *
         * @param keys keys the task must write to its own namespace
         * @return this builder
         */
        public Builder requires(String... keys) {
            this.requiredOutputs.addAll(List.of(keys));
            return this;
        }

        /**
         * Adds policy tags.
         *
         * @param values tags
         * @return this builder
         */
        public Builder tags(String... values) {
            this.tags.addAll(List.of(values));
            return this;
        }

        /**
         * Builds the immutable spec.
         *
         * @return the task spec
         */
        public TaskSpec build() {
            return new TaskSpec(id, stage, dependsOn, risk, destructive, retry, agent, fallback, compensation,
                    requiredOutputs, tags);
        }
    }
}
