package com.testframe.plugin.format;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamesTest {

    @Test
    void slugIsLowercaseKebabWithoutAccents() {
        assertEquals("acao-rapida", Names.slug("Ação Rápida!"));
        assertEquals("active-products-echo", Names.slug("  Active products / echo "));
        assertEquals("unnamed", Names.slug(""));
        assertEquals("unnamed", Names.slug("---"));
    }

    @Test
    void varNameFromTitle() {
        assertEquals("getActiveProducts", Names.toVarName("Get active products"));
        assertEquals("unnamed", Names.toVarName(""));
    }

    @Test
    void camelCaseToKebabAndBack() {
        String[][] pairs = {
                {"getActiveProducts", "get-active-products"},
                {"assertEchoProcessed", "assert-echo-processed"},
                {"step2Value", "step2-value"},
                {"x1", "x1"},
        };
        for (String[] pair : pairs) {
            assertEquals(pair[1], Names.varNameToFileName(pair[0]));
            assertEquals(pair[0], Names.fileNameToVarName(pair[1]));
            assertEquals(pair[0], Names.fileNameToVarName(pair[1] + ".md"));
            assertTrue(Names.isRoundTrip(pair[0]), pair[0]);
        }
    }

    @Test
    void consecutiveCapitalsDoNotSurviveTheRoundTrip() {
        assertEquals("get-httpdata", Names.varNameToFileName("getHTTPData"));
        assertFalse(Names.isRoundTrip("getHTTPData"));
        assertEquals("getHttpdata", Names.normalizeVarName("getHTTPData"));
        assertEquals("getHttpData", Names.normalizeVarName("getHTTP data"));
        assertEquals("card", Names.varNameToFileName(""));
        assertEquals("run-sh", Names.varNameToFileName("run.sh"));
        assertFalse(Names.isRoundTrip("run.sh"));
    }

    @Test
    void trimFollowsJavascript() {
        assertEquals("a b", Names.trim(" \t a b \n"));
        assertEquals("", Names.trim(null));
        assertTrue(Names.isBlank(" \n "));
        assertTrue(Names.isBlank(null));
    }
}