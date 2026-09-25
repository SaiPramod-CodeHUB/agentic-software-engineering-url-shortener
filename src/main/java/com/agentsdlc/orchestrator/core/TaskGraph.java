package com.agentsdlc.orchestrator.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * An immutable, validated DAG of {@link TaskSpec}s.
 *
 * <p>Validation happens in the constructor (duplicate ids, unknown
 * dependencies, cycles) so a malformed plan fails before any agent runs —
 * never halfway through a pipeline with side effects already applied.</p>
 */
public final class TaskGraph {

    private final Map<String, TaskSpec> tasks;
    private final Map<String, Set<String>> dependents;
    private final List<List<TaskSpec>> waves;

    /**
     * Builds and validates the graph.
     *
     * @param specs the tasks
     * @throws GraphValidationException when the graph is malformed
     */
    public TaskGraph(Collection<TaskSpec> specs) {
        Map<String, TaskSpec> byId = new LinkedHashMap<>();
        for (TaskSpec spec : specs) {
            if (byId.putIfAbsent(spec.id(), spec) != null) {
                throw new GraphValidationException("duplicate task id: " + spec.id());
            }
        }
        Map<String, Set<String>> down = new HashMap<>();
        byId.keySet().forEach(id -> down.put(id, new TreeSet<>()));
        for (TaskSpec spec : byId.values()) {
            for (String dep : spec.dependsOn()) {
                if (!byId.containsKey(dep)) {
                    throw new GraphValidationException("task '" + spec.id() + "' depends on unknown task '" + dep + "'");
                }
                down.get(dep).add(spec.id());
            }
        }
        this.tasks = Collections.unmodifiableMap(byId);
        this.dependents = down;
        this.waves = computeWaves();
    }

    /**
     * Topological layering (Kahn's algorithm). Each wave contains only tasks
     * whose dependencies are all in earlier waves, so a wave can run fully in
     * parallel and must be joined before the next starts. Ids are sorted
     * within a wave so the plan is deterministic.
     */
    private List<List<TaskSpec>> computeWaves() {
        Map<String, Integer> indegree = new TreeMap<>();
        tasks.values().forEach(t -> indegree.put(t.id(), t.dependsOn().size()));
        List<String> ready = new ArrayList<>();
        indegree.forEach((id, deg) -> {
            if (deg == 0) {
                ready.add(id);
            }
        });
        List<List<TaskSpec>> result = new ArrayList<>();
        int placed = 0;
        while (!ready.isEmpty()) {
            Collections.sort(ready);
            result.add(ready.stream().map(tasks::get).toList());
            placed += ready.size();
            List<String> next = new ArrayList<>();
            for (String id : ready) {
                for (String child : dependents.get(id)) {
                    if (indegree.merge(child, -1, Integer::sum) == 0) {
                        next.add(child);
                    }
                }
            }
            ready.clear();
            ready.addAll(next);
        }
        if (placed != tasks.size()) {
            Set<String> cyclic = new TreeSet<>();
            indegree.forEach((id, deg) -> {
                if (deg > 0) {
                    cyclic.add(id);
                }
            });
            throw new GraphValidationException("dependency cycle among tasks " + cyclic);
        }
        return List.copyOf(result);
    }

    /**
     * Returns the execution waves.
     *
     * @return waves in execution order; tasks within a wave are independent
     */
    public List<List<TaskSpec>> waves() {
        return waves;
    }

    /**
     * Returns all tasks in declaration order.
     *
     * @return the tasks
     */
    public Collection<TaskSpec> tasks() {
        return tasks.values();
    }

    /**
     * Looks up a task.
     *
     * @param id task id
     * @return the task
     * @throws IllegalArgumentException when unknown
     */
    public TaskSpec get(String id) {
        TaskSpec spec = tasks.get(id);
        if (spec == null) {
            throw new IllegalArgumentException("unknown task " + id);
        }
        return spec;
    }

    /**
     * Returns every task transitively downstream of the given roots,
     * excluding the roots themselves.
     *
     * @param roots the changed tasks
     * @return the transitive dependents, sorted
     */
    public Set<String> downstreamOf(Collection<String> roots) {
        Set<String> seen = new TreeSet<>();
        Deque<String> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            for (String child : dependents.getOrDefault(queue.poll(), Set.of())) {
                if (seen.add(child)) {
                    queue.add(child);
                }
            }
        }
        seen.removeAll(roots);
        return seen;
    }
}
