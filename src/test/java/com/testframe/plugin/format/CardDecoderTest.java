package com.testframe.plugin.format;

import com.testframe.plugin.model.Card;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardDecoderTest {

    private static CardDecoder.Decoded decode(String fileBase, String content) {
        return CardDecoder.decode(fileBase, content);
    }

    private static String card(String frontmatter, String code) {
        return "-------------------\n" + frontmatter + "-------------------\n```json\n" + code + "\n```\n";
    }

    @Test
    void decodesALiteralCard() {
        Card card = decode("product-payload", card("title: Product payload\nsource: rawvalue\nsubtype: json\ntimeout: 2000\n", "{\"nome\": \"x\"}")).card();
        assertEquals("productPayload", card.varName());
        assertEquals("Product payload", card.title());
        assertEquals("rawvalue", card.sourceType());
        assertNull(card.sourceName());
        assertEquals("json", card.subtype());
        assertEquals(2000, card.timeout());
        assertEquals("{\"nome\": \"x\"}", card.code());
    }

    @Test
    void decodesAMongoCardWithANamedSource() {
        Card card = decode("get-active-products", card("title: Get active products\nsource: mongo/products-db\nsubtype: find\ncollection: products\n", "[{\"ativo\": true}]")).card();
        assertEquals("getActiveProducts", card.varName());
        assertEquals("mongo", card.sourceType());
        assertEquals("products-db", card.sourceName());
        assertEquals("mongo/products-db", card.sourceRef());
        assertEquals("products", card.collection());
    }

    @Test
    void reportsSchemaProblemsWithoutLosingTheCard() {
        CardDecoder.Decoded decoded = decode("x", card("source: mongo/db\n", "[]"));
        assertTrue(decoded.errors().contains("title: required"));
        assertTrue(decoded.errors().contains("subtype: required for mongo (find, aggregation, deleteOne, deleteMany, updateOne, updateMany, insertOne, insertMany)"));
        assertTrue(decoded.errors().contains("collection: required on mongo cards (the collection the card works on)"));
        assertEquals("x", decoded.card().varName());
    }

    @Test
    void validatesKnownRules() {
        assertTrue(decode("a", card("title: A\nsource: nope\n", "")).errors().contains("source: unknown type-source"));
        assertTrue(decode("a", card("title: A\nsource: rawvalue\nsubtype: find\n", "")).errors().contains("subtype: rawvalue has no subtypes"));
        assertTrue(decode("a", card("title: A\nsource: rawvalue\ncollection: x\n", "")).errors().contains("collection: only mongo cards name a collection"));
        assertTrue(decode("a", card("title: A\nsource: rawvalue\nopenapi-id: op\n", "")).errors().contains("openapi-id: only openapi cards name a spec operation"));
        assertTrue(decode("a", card("title: A\nsource: rawvalue\ntimeout: 50\n", "")).errors().contains("timeout: must be >= 100"));
        assertTrue(decode("a", card("title: A\nsource: rawvalue\nscript: x\n", "")).errors().contains("script: sources have no scripts; the card holds its own code"));
    }

    @Test
    void aSourcelessTypeRejectsANameSource() {
        assertTrue(decode("a", card("title: A\nsource: rawvalue/x\n", "")).errors().contains("source: rawvalue cards have no name-source (write source: rawvalue)"));
    }
}