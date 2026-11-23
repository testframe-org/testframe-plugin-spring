package com.testframe.plugin.engine;

import com.testframe.plugin.Json;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The forms one secret is matched in (agents/specs/runner-protocol.md, "Security"): raw, JSON-escaped and
 * URL-encoded (Java's form encoding and the browser's {@code encodeURIComponent}, each in upper and
 * lower-case hex), longest first, with secrets shorter than {@link Redactor#MIN_SUBSTRING} characters
 * left out of the substring match. The structural rules (URI user-info, the four sensitive header
 * names) hold whatever the secret list is. What actually leaves the plugin is covered end to end
 * by {@code api/RedactionBoundaryTest}.
 */
class RedactorTest {

    private static String apply(List<String> secrets, String text) {
        return new Redactor(secrets).apply(text);
    }

    @Test
    void replacesTheRawValue() {
        assertEquals("token=***", apply(List.of("s3cretToken"), "token=s3cretToken"));
    }

    @Test
    void replacesTheJsonEscapedForm() {
        // the secret holds a quote, so a serialized body carries its JSON-escaped form
        assertEquals("{\"v\":\"***\"}", apply(List.of("s3\"cret"), "{\"v\":\"s3\\\"cret\"}"));
    }

    @Test
    void replacesBothUrlEncodedForms() {
        List<String> secrets = List.of("my secret token");
        assertEquals("?q=***&x=1", apply(secrets, "?q=my+secret+token&x=1"));
        assertEquals("?q=***&x=1", apply(secrets, "?q=my%20secret%20token&x=1"));
    }

    /**
     * A browser's {@code encodeURIComponent} is not Java's {@code URLEncoder}: it writes a space as
     * {@code %20} and leaves {@code !'()*~} alone. A secret holding one of those arrives from a
     * system under test in a form no Java encoder produces.
     */
    @Test
    void replacesTheBrowserUrlEncoding() {
        List<String> secrets = List.of("a b'c!");
        assertEquals("?q=***", apply(secrets, "?q=a%20b'c!"));
        assertEquals("?q=***", apply(secrets, "?q=a%20b%27c%21"), "the Java form encoding is matched too");
        assertEquals("?q=***", apply(secrets, "?q=a+b%27c%21"));
    }

    /** Both encoders emit upper-case hex; a URL that travelled through another tool often does not. */
    @Test
    void replacesLowercasePercentEscapes() {
        List<String> secrets = List.of("user/p@ss word");
        assertEquals("***", apply(secrets, "user%2Fp%40ss%20word"));
        assertEquals("***", apply(secrets, "user%2fp%40ss%20word"), "lower-case hex");
        assertEquals("***", apply(secrets, "user%2fp%40ss+word"));
    }

    @Test
    void theLongestSecretIsReplacedFirst() {
        assertEquals("***", apply(List.of("secret", "secret-value"), "secret-value"));
    }

    @Test
    void aSecretShorterThanTheMinimumIsNotSubstringMatched() {
        Redactor redactor = new Redactor(List.of("abc", "wxyz"));
        assertTrue(redactor.hasShortSecret());
        assertEquals("prefix abc suffix", redactor.apply("prefix abc suffix"));
        assertEquals("prefix *** suffix", redactor.apply("prefix wxyz suffix"));
    }

    @Test
    void aShortPasswordIsStillRemovedFromAUri() {
        // agents/specs/runner-protocol.md: a short secret is "still removed where it sits structurally"
        assertEquals("mongodb://***@host/db", new Redactor(List.of("abc")).apply("mongodb://app:abc@host/db"));
    }

    @Test
    void uriUserInfoIsMaskedWithoutAnySecret() {
        Redactor redactor = Redactor.structural();
        assertFalse(redactor.hasShortSecret());
        assertEquals("mongodb://***@localhost:27017/products",
                redactor.apply("mongodb://app:s3cret@localhost:27017/products"));
    }

    @Test
    void sensitiveHeaderValuesAreMaskedWhateverTheCase() {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer visibleToken");
        headers.put("PROXY-AUTHORIZATION", "Basic visibleToken");
        headers.put("Cookie", "session=visibleToken");
        headers.put("set-cookie", "session=visibleToken; Path=/");
        headers.put("x-api-key", "visibleToken");
        JsonNode out = Redactor.structural().tree(Map.of("headers", headers), Json.MAPPER).get("headers");
        assertEquals("***", out.get("Authorization").asString());
        assertEquals("***", out.get("PROXY-AUTHORIZATION").asString());
        assertEquals("***", out.get("Cookie").asString());
        assertEquals("***", out.get("set-cookie").asString());
        assertEquals("visibleToken", out.get("x-api-key").asString(), "an ordinary header is left alone");
    }

    @Test
    void aSensitiveKeyIsMaskedWhereverItSitsInTheTree() {
        JsonNode tree = Redactor.structural()
                .tree(Map.of("detail", Map.of("request", Map.of("authorization", "Bearer visibleToken"))), Json.MAPPER);
        assertEquals("***", tree.get("detail").get("request").get("authorization").asString());
    }

    @Test
    void everyStringOfTheTreeIsRedactedIncludingKeys() {
        JsonNode tree = new Redactor(List.of("s3cretToken"))
                .tree(Map.of("s3cretToken", List.of("plain s3cretToken", 7)), Json.MAPPER);
        assertFalse(tree.has("s3cretToken"), "the key is redacted too");
        assertTrue(tree.has("***"));
        assertEquals("plain ***", tree.get("***").get(0).asString());
    }

    @Test
    void nullAndEmptyTextAreReturnedAsIs() {
        Redactor redactor = new Redactor(List.of("s3cretToken"));
        assertNull(redactor.apply(null));
        assertEquals("", redactor.apply(""));
    }
}
