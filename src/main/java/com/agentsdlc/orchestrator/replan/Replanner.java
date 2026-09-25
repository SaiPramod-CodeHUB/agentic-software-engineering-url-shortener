package com.agentsdlc.orchestrator.replan;

import com.agentsdlc.orchestrator.audit.AuditLog;
import com.agentsdlc.orchestrator.core.TaskGraph;
import com.agentsdlc.orchestrator.core.TaskSpec;
import com.agentsdlc.orchestrator.state.StateStore;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Dynamic re-planning driven by output hashes.
 *
 * <p>Take a {@link #fingerprint()} before an upstream task is re-run, then
 * call {@link #replan} afterwards. Every task whose output hash changed is a
 * root; the transitive downstream closure of the roots is invalidated
 * (namespace cleared) and returned as the scope of the next run. Everything
 * else — including human decisions stored in reserved namespaces — is kept.
 * The scope is written to the audit log before anything re-runs, so what
 * will be redone is explicit and reviewable, never silent.</p>
 */
public final class Replanner {

    /**
     * A re-plan decision.
     *
     * @param changed     tasks whose outputs changed (the roots)
     * @param invalidated downstream tasks that must re-run
     * @param preserved   tasks whose outputs are reused
     */
    public record Plan(Set<String> changed, Set<String> invalidated, Set<String> preserved) {

        /**
         * Copies the sets.
         *
         * @param changed     changed tasks
         * @param invalidated invalidated tasks
         * @param preserved   preserved tasks
         */
        public Plan {
            changed = Collections.unmodifiableSortedSet(new TreeSet<>(changed));
            invalidated = Collections.unmodifiableSortedSet(new TreeSet<>(invalidated));
            preserved = Collections.unmodifiableSortedSet(new TreeSet<>(preserved));
        }
    }

    private final TaskGraph graph;
    private final StateStore state;

    /**
     * Creates a re-planner.
     *
     * @param graph the plan
     * @param state the state store holding task outputs
     */
    public Replanner(TaskGraph graph, StateStore state) {
        this.graph = graph;
        this.state = state;
    }

    /**
     * Captures the current output hash of every task.
     *
     * @return task id to namespace hash
     */
    public Map<String, String> fingerprint() {
        Map<String, String> hashes = new TreeMap<>();
        for (TaskSpec task : graph.tasks()) {
            hashes.put(task.id(), state.hash(task.id()));
        }
        return hashes;
    }

    /**
     * Compares against an earlier fingerprint, invalidates the downstream
     * closure of every changed task and audits the decision.
     *
     * @param before fingerprint taken before the upstream change
     * @param audit  audit log
     * @param runId  run id to attribute the event to
     * @return the plan; {@code invalidated} is the scope for the next run
     */
    public Plan replan(Map<String, String> before, AuditLog audit, String runId) {
        Map<String, String> after = fingerprint();
        Set<String> changed = new TreeSet<>();
        after.forEach((id, hash) -> {
            if (!hash.equals(before.get(id))) {
                changed.add(id);
            }
        });
        Set<String> invalidated = graph.downstreamOf(changed);
        Set<String> preserved = new TreeSet<>(after.keySet());
        preserved.removeAll(changed);
        preserved.removeAll(invalidated);
        for (String id : invalidated) {
            state.clear(id);
            state.put(StateStore.STATUS_NS, id, "PENDING");
        }
        audit.record(runId, "REPLAN", null, Map.of(
                "changed", changed.stream().toList(),
                "invalidated", invalidated.stream().toList(),
                "preserved", preserved.stream().toList()));
        return new Plan(changed, invalidated, preserved);
    }
}
