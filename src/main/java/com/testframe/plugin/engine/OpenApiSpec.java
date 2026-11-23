package com.testframe.plugin.engine;

import com.testframe.plugin.model.Operation;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What an {@code openapi} source offers to its cards, distilled from its OpenAPI (or Swagger 2)
 * JSON document (agents/specs/project-format.md, "OpenAPI sources and their operations"): the API base
 * ({@code servers[0].url} resolved against the spec URL; the spec URL's origin when the document
 * names no server) and the operations. The plugin fetches a spec only for tour and CLI runs, once
 * per run, in memory; runner-protocol requests carry the operations.
 */
public record OpenApiSpec(String specUrl, String apiBase, List<Operation> operations) {

    private static final List<String> METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Parses a spec document; IllegalArgumentException when it is not one. */
    public static OpenApiSpec parse(String specUrl, String document) {
        JsonNode root;
        try {
            root = JSON.readTree(document == null ? "" : document);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("the spec at " + specUrl + " is not JSON: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject() || !root.path("paths").isObject()) {
            throw new IllegalArgumentException("the document at " + specUrl + " is not an OpenAPI spec (no \"paths\")");
        }
        List<Operation> operations = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : root.path("paths").properties()) {
            if (!path.getValue().isObject()) {
                continue;
            }
            for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
                String method = op.getKey().toUpperCase(Locale.ROOT);
                if (METHODS.contains(method) && op.getValue().isObject()) {
                    operations.add(new Operation(text(op.getValue(), "operationId"), method, path.getKey(),
                            text(op.getValue(), "summary")));
                }
            }
        }
        return new OpenApiSpec(specUrl, apiBaseOf(specUrl, root), List.copyOf(operations));
    }

    private static String apiBaseOf(String specUrl, JsonNode root) {
        URI spec = URI.create(specUrl);
        String origin = spec.getScheme() + "://" + spec.getRawAuthority();
        String server = text(root.path("servers").path(0), "url");
        if (server.isEmpty()) {
            String host = text(root, "host"); // Swagger 2: host + basePath
            return host.isEmpty() ? origin : spec.getScheme() + "://" + host + text(root, "basePath");
        }
        return spec.resolve(server).toString().replaceAll("/+$", "");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : "";
    }
}
