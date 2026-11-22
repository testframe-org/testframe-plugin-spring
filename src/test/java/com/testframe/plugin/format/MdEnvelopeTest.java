package com.testframe.plugin.format;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MdEnvelopeTest {

    @Test
    void parsesFieldsAndBody() {
        MdEnvelope.Parsed md = MdEnvelope.parse("""
                -------------------
                title: Get data
                base-url: mongodb://localhost:27017/x
                empty:
                not a field
                -------------------
                line 1

                line 3


                """);
        assertEquals(Map.of("title", "Get data", "base-url", "mongodb://localhost:27017/x", "empty", ""), md.fields());
        assertEquals("line 1\n\nline 3", md.body());
    }

    @Test
    void handlesCrlf() {
        MdEnvelope.Parsed md = MdEnvelope.parse("-------------------\r\ntitle: A\r\n-------------------\r\n\r\nbody\n");
        assertEquals("A", md.fields().get("title"));
        assertEquals("\nbody", md.body());
    }

    @Test
    void withoutFrontmatterEverythingIsBody() {
        MdEnvelope.Parsed md = MdEnvelope.parse("title: A\nfree text");
        assertEquals(Map.of(), md.fields());
        assertEquals("title: A\nfree text", md.body());
        assertEquals("", MdEnvelope.parse(null).body());
    }

    @Test
    void unfenceStripsTheFencedBlock() {
        assertEquals("assert $count(activeProducts) > 0",
                MdEnvelope.unfence("```jsonassert\nassert $count(activeProducts) > 0\n```"));
        assertEquals("plain text", MdEnvelope.unfence("plain text"));
        assertEquals("", MdEnvelope.unfence("```json\n```"));
        assertEquals("[{\"a\": 1}]", MdEnvelope.unfence("```json\n[{\"a\": 1}]\n```"));
    }
}