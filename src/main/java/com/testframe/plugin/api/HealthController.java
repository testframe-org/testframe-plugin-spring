package com.testframe.plugin.api;

import com.testframe.plugin.PluginInfo;
import com.testframe.plugin.engine.Limits;
import com.testframe.plugin.format.MongoCall;
import com.testframe.plugin.project.ProjectsRoot;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/v1/health} (agents/specs/runner-protocol.md): name, version, protocol and the
 * capabilities (the runnable (type, subtypes), the simulated types and the fixed limits). It
 * never carries configuration values.
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final ProjectsRoot projects;

    public HealthController(ProjectsRoot projects) {
        this.projects = projects;
    }

    @GetMapping("/health")
    public void health(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Map<String, Object> runnable = new LinkedHashMap<>();
        runnable.put("openapi", List.of("restapi", "swagger", "graphql"));
        runnable.put("mongo", MongoCall.SUBTYPES);
        runnable.put("jsonassert", List.of("script", "conditional"));
        runnable.put("rawvalue", List.of("json"));

        Map<String, Object> simulated = new LinkedHashMap<>();
        simulated.put("sql", List.of("oracle", "postgres", "mysql", "sqlserver"));
        simulated.put("kafka", List.of());
        simulated.put("grpc", List.of());

        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxCardTimeoutMs", Limits.MAX_CARD_TIMEOUT_MS);
        limits.put("maxRunMs", Limits.MAX_RUN_MS);
        limits.put("maxRequestBytes", Limits.MAX_REQUEST_BYTES);
        limits.put("maxResponseBodyBytes", Limits.MAX_RESPONSE_BODY_BYTES);
        limits.put("maxMongoDocuments", Limits.MAX_MONGO_DOCUMENTS);

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("runnable", runnable);
        capabilities.put("simulated", simulated);
        capabilities.put("tour", projects.available());
        capabilities.put("limits", limits);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "up");
        body.put("name", PluginInfo.NAME);
        body.put("version", PluginInfo.VERSION);
        body.put("protocol", PluginInfo.PROTOCOL);
        body.put("capabilities", capabilities);

        ApiResponses.json(response, 200, body, ApiResponses.redactor(request));
    }
}