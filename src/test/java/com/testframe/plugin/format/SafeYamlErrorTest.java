package com.testframe.plugin.format;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A malformed {@code sources*.yaml} layer must not echo its content in the error: secrets cannot
 * be derived from an unparsable private layer, so the redactor could not mask them afterwards.
 * Every document below holds {@link #SECRET} at the place the parser fails.
 */
class SafeYamlErrorTest {

    private static final String SECRET = "Sup3rS3cretApiKey";

    @ParameterizedTest
    @ValueSource(strings = {
            // unterminated quoted scalar
            "openapi:\n  echo-api:\n    headers:\n      x-api-key: \"Sup3rS3cretApiKey\n",
            // mapping value where none is allowed
            "openapi:\n  echo-api:\n    headers:\n      x-api-key: Sup3rS3cretApiKey: more\n",
            // bad indentation after the value
            "openapi:\n  echo-api:\n    headers:\n      x-api-key: Sup3rS3cretApiKey\n     broken: x\n",
            // undefined tag handle carrying the secret
            "openapi:\n  echo-api:\n    headers:\n      x-api-key: !Sup3rS3cretApiKey!x value\n",
            // tab that cannot start a token, right after the secret
            "openapi:\n  echo-api:\n    headers:\n      x-api-key: [Sup3rS3cretApiKey\n\t: x\n",
            // unknown escape inside the secret
            "openapi:\n  echo-api:\n    headers:\n      x-api-key: \"Sup3rS3cret\\qApiKey\"\n",
            // flow sequence never closed
            "openapi:\n  echo-api:\n    headers:\n      x-api-key: [Sup3rS3cretApiKey, \n",
            // anchor-like garbage
            "openapi:\n  echo-api:\n    headers:\n      x-api-key: &Sup3rS3cretApiKey\n        - *Sup3rS3cretApiKey\n",
            // a URL with credentials and a stray quote
            "openapi:\n  echo-api:\n    base-url: https://user:Sup3rS3cretApiKey@host/spec.json'\n    x: 'y\n",
    })
    void aParseErrorNeverEchoesTheDocument(String yaml) {
        SafeYaml.YamlProblem problem = assertThrows(SafeYaml.YamlProblem.class, () -> SafeYaml.load(yaml));
        String message = problem.getMessage();
        assertFalse(message.contains(SECRET), () -> "the YAML error echoed the secret: " + message);
        assertFalse(message.contains("Sup3r"), () -> "the YAML error echoed part of the secret: " + message);
        assertTrue(message.contains("line "), () -> "the YAML error names its line: " + message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sources.private.yaml", "sources.local.private.yaml"})
    void aBrokenPrivateLayerReportsNoSecret(String file) {
        SourcesDecoder.Sources sources = SourcesDecoder.decode(Map.of(
                "sources.yaml", "environments: [local]\nopenapi:\n  echo-api:\n    base-url: http://127.0.0.1/spec.json\n",
                file, "openapi:\n  echo-api:\n    headers:\n      x-api-key: \"" + SECRET + "\n"));
        List<String> errors = sources.errors();
        assertFalse(errors.isEmpty(), "the broken layer is an error");
        assertTrue(errors.stream().anyMatch(e -> e.startsWith(file + ": ")), () -> "the error names the file: " + errors);
        assertTrue(errors.stream().noneMatch(e -> e.contains(SECRET)), () -> "the error echoed the secret: " + errors);
    }

    /** A parsable but non-string private value (a number, a boolean) is an error that names no value. */
    @ParameterizedTest
    @ValueSource(strings = {"88123456789", "true"})
    void aNonStringPrivateValueIsNotEchoed(String value) {
        SourcesDecoder.Sources sources = SourcesDecoder.decode(Map.of(
                "sources.yaml", "environments: [local]\nopenapi:\n  echo-api:\n    base-url: http://127.0.0.1/spec.json\n",
                "sources.local.private.yaml", "openapi:\n  echo-api:\n    headers:\n      x-api-key: " + value + "\n"));
        List<String> errors = sources.errors();
        assertTrue(errors.stream().anyMatch(e -> e.contains("must be a string")), () -> "the value is rejected: " + errors);
        assertTrue(errors.stream().noneMatch(e -> e.contains(value)), () -> "the error echoed the value: " + errors);
    }
}
