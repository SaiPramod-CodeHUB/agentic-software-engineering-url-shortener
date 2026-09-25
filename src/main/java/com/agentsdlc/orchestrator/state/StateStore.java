package com.agentsdlc.orchestrator.state;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, namespaced key/value store: the only channel between agents.
 *
 * <p>Each task writes to the namespace named after its id and may read any
 * namespace. Values are strings (text, JSON, Markdown) so a namespace can be
 * hashed for change detection, snapshotted for rollback, and persisted
 * without a serialisation framework. Namespaces starting with {@code _} are
 * reserved for the engine (task status, approvals, human input).</p>
 */
public final class StateStore {

    /** Reserved namespace holding each task's last terminal status. */
    public static final String STATUS_NS = "_status";
    /** Reserved namespace holding human approval decisions per task. */
    public static final String APPROVALS_NS = "_approvals";
    /** Reserved namespace holding human-provided input (e.g. clarifications). */
    public static final String HUMAN_NS = "_human";

    private final Map<String, Map<String, String>> data = new ConcurrentHashMap<>();

    /** Creates an empty store. */
    public StateStore() {
        // Empty store; namespaces are created on first write.
    }

    /**
     * Writes a value.
     *
     * @param namespace namespace (usually a task id)
     * @param key       key within the namespace
     * @param value     value; must not be {@code null}
     */
    public void put(String namespace, String key, String value) {
        if (value == null) {
            throw new IllegalArgumentException("null values are not allowed: " + namespace + "/" + key);
        }
        data.computeIfAbsent(namespace, ns -> new ConcurrentHashMap<>()).put(key, value);
    }

    /**
     * Reads a value.
     *
     * @param namespace namespace
     * @param key       key
     * @return the value, if present
     */
    public Optional<String> get(String namespace, String key) {
        Map<String, String> ns = data.get(namespace);
        return ns == null ? Optional.empty() : Optional.ofNullable(ns.get(key));
    }

    /**
     * Returns a sorted, immutable copy of a namespace.
     *
     * @param namespace namespace
     * @return copy of its entries (empty if absent)
     */
    public Map<String, String> snapshot(String namespace) {
        Map<String, String> ns = data.get(namespace);
        return ns == null ? Map.of() : Map.copyOf(new TreeMap<>(ns));
    }

    /**
     * Replaces a namespace with a previous snapshot (used by rollback).
     *
     * @param namespace namespace
     * @param snapshot  entries to restore; empty removes the namespace
     */
    public void restore(String namespace, Map<String, String> snapshot) {
        if (snapshot.isEmpty()) {
            data.remove(namespace);
        } else {
            data.put(namespace, new ConcurrentHashMap<>(snapshot));
        }
    }

    /**
     * Deletes a namespace (used when re-planning invalidates a task).
     *
     * @param namespace namespace
     */
    public void clear(String namespace) {
        data.remove(namespace);
    }

    /**
     * Returns the names of all namespaces.
     *
     * @return sorted namespace names
     */
    public Set<String> namespaces() {
        return new TreeSet<>(data.keySet());
    }

    /**
     * Content hash of a namespace; equal content always yields an equal hash.
     *
     * @param namespace namespace
     * @return SHA-256 hex over the sorted entries
     */
    public String hash(String namespace) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, String> e : new TreeMap<>(snapshot(namespace)).entrySet()) {
                // Length-prefixing prevents ambiguity such as ("ab","c") vs ("a","bc").
                update(md, e.getKey());
                update(md, e.getValue());
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void update(MessageDigest md, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        md.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
        md.update((byte) ':');
        md.update(bytes);
    }
}
