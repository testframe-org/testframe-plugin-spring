package com.testframe.plugin.model;

/**
 * One card as it should run (agents/specs/runner-protocol.md, "Card (request)"). Layout and derived
 * fields are not part of the protocol; a card's {@code .private.md} notes are never read.
 * {@code sourceName} is the {@code {name}} of {@code {type}/{name}} for named sources;
 * {@code code} is the card body without fences (mongo: the JSON array of call arguments).
 */
public record Card(
        String varName,
        String title,
        String sourceType,
        String sourceName,
        String subtype,
        String collection,
        String endpoint,
        String openapiId,
        Integer timeout,
        String code
) {

    public Card withCode(String newCode) {
        return new Card(varName, title, sourceType, sourceName, subtype, collection, endpoint, openapiId, timeout, newCode);
    }

    /** {@code {type}/{name}}, or the type alone when the card names no source. */
    public String sourceRef() {
        String type = sourceType == null ? "" : sourceType.trim();
        String name = sourceName == null ? "" : sourceName.trim();
        return name.isEmpty() ? type : type + "/" + name;
    }
}
