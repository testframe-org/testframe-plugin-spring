package com.testframe.plugin.model;

import java.util.List;
import java.util.Map;

/**
 * One resolved source of a run request (docs/runner-protocol.md, "Sources (request)"), keyed by
 * {@code {type}/{name}}: openapi {@code {apiBase, headers, operations}}, mongo {@code {uri}},
 * sql/kafka/grpc {@code {url}}. {@code headers} are sent with every request of the source's
 * cards (names lower-case).
 */
public record SourceConnection(String apiBase, Map<String, String> headers, List<Operation> operations,
                               String uri, String url) {

    public static SourceConnection openapi(String apiBase, Map<String, String> headers, List<Operation> operations) {
        return new SourceConnection(apiBase, headers, operations, null, null);
    }

    public static SourceConnection mongo(String uri) {
        return new SourceConnection(null, null, null, uri, null);
    }

    public static SourceConnection other(String url) {
        return new SourceConnection(null, null, null, null, url);
    }
}
