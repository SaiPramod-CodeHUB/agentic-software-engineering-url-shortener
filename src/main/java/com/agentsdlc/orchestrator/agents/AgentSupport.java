package com.agentsdlc.orchestrator.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small helpers shared by agents: JSON (de)serialisation and Java source introspection. */
final class AgentSupport {

    static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;");
    private static final Pattern TYPE = Pattern.compile("(?m)^(?:public\\s+)?(?:final\\s+)?class\\s+(\\w+)");

    private AgentSupport() {
    }

    static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    static List<String> jsonList(String json) {
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("expected a JSON array", e);
        }
    }

    static Map<String, String> jsonMap(String json) {
        try {
            return JSON.readValue(json, new TypeReference<Map<String, String>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("expected a JSON object", e);
        }
    }

    /** Fully-qualified name of the first top-level class in a source file. */
    static String qualifiedClassName(String source) {
        Matcher pkg = PACKAGE.matcher(source);
        Matcher type = TYPE.matcher(source);
        if (!type.find()) {
            throw new IllegalArgumentException("no class declaration found in generated source");
        }
        return (pkg.find() ? pkg.group(1) + "." : "") + type.group(1);
    }

    /** Path of a source file relative to a source root, derived from its qualified name. */
    static String sourcePath(String qualifiedName) {
        return qualifiedName.replace('.', '/') + ".java";
    }
}
