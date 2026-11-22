package com.testframe.plugin.model;

import java.util.Map;

/**
 * Outcome of one card (docs/runner-protocol.md, "CardResult"): {@code status} success, error or
 * skipped; {@code text} the one-line log {@code [Step n] {title}: {summary}}; {@code output} the
 * payload as text; {@code ranAt} the ISO-8601 instant the card started; {@code offsetMs} from the
 * start of the run; {@code elapsedMs} the card's own duration; {@code code} only when the run
 * rewrote this card's body (a result card); {@code skippedBy} the conditional that skipped it;
 * {@code detail} the structured evidence by card kind.
 */
public record CardResult(String varName, String status, String text, String output, String ranAt,
                         long offsetMs, long elapsedMs, String code, String skippedBy,
                         Map<String, Object> detail) {

    public static final String SUCCESS = "success";
    public static final String ERROR = "error";
    public static final String SKIPPED = "skipped";

    public CardResult withCode(String newCode) {
        return new CardResult(varName, status, text, output, ranAt, offsetMs, elapsedMs, newCode, skippedBy, detail);
    }

    public boolean isError() {
        return ERROR.equals(status);
    }
}
