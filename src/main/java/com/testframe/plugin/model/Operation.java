package com.testframe.plugin.model;

/** One operation of an OpenAPI spec: {@code operationId} is its identity (a card's {@code openapi-id}). */
public record Operation(String operationId, String method, String path, String summary) {
}
