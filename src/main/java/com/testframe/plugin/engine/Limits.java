package com.testframe.plugin.engine;

/** The plugin's fixed limits, served in {@code GET /api/v1/health} (docs/runner-protocol.md). */
public final class Limits {

    public static final int MIN_CARD_TIMEOUT_MS = 100;
    public static final int MAX_CARD_TIMEOUT_MS = 120_000;
    public static final long MAX_RUN_MS = 600_000;
    public static final long MAX_REQUEST_BYTES = 10L * 1024 * 1024;
    public static final int MAX_RESPONSE_BODY_BYTES = 5 * 1024 * 1024;
    public static final int MAX_MONGO_DOCUMENTS = 1000;

    private Limits() {
    }
}
