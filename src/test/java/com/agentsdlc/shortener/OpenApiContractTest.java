package com.agentsdlc.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * Fails when {@code docs/openapi.yaml} and the controller's actual routes
 * diverge, so the published contract cannot silently rot.
 */
@SpringBootTest
@ActiveProfiles("test")
class OpenApiContractTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    @SuppressWarnings("unchecked")
    void documentedOperationsMatchImplementedRoutes() throws Exception {
        Map<String, Object> spec;
        try (Reader in = Files.newBufferedReader(Path.of("docs/openapi.yaml"))) {
            spec = new Yaml().load(in);
        }
        Set<String> documented = new TreeSet<>();
        ((Map<String, Map<String, Object>>) spec.get("paths")).forEach((path, ops) ->
                ops.keySet().forEach(method -> documented.add(method.toUpperCase() + " " + path)));

        Set<String> implemented = new TreeSet<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            if (handler.getBeanType().getPackageName().startsWith("com.agentsdlc")) {
                info.getPathPatternsCondition().getPatternValues().forEach(path ->
                        info.getMethodsCondition().getMethods().forEach(m -> implemented.add(m.name() + " " + path)));
            }
        });
        assertThat(implemented).isEqualTo(documented);
    }
}
