package com.testframe.plugin.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every line the CLI prints to stderr passes through the redactor (docs/runner-protocol.md,
 * "Security"; docs/test-tour.md, "Redaction"): the structural rules always, and the secrets of the
 * environment's private layers once the sources resolve. Nothing here starts a server.
 */
class CliStderrRedactionTest {

    private static final String SECRET = "Sup3rS3cretApiKey";

    @TempDir
    Path root;

    @Test
    void aSourcesErrorHasItsUriUserInfoMasked() throws IOException {
        Path project = project("""
                environments: [local]
                https://admin:%s@db.example: {}
                """.formatted(SECRET), null);
        Run run = run(project, "--env", "local");
        assertEquals(2, run.exit(), run.err());
        assertTrue(run.err().contains("https://***@db.example"), run.err());
        assertFalse(run.err().contains(SECRET), () -> "stderr leaked the secret: " + run.err());
    }

    @Test
    void aProblemLineHasTheEnvironmentSecretsMasked() throws IOException {
        Path project = project("""
                environments: [local]
                openapi:
                  echo-api:
                    base-url: http://127.0.0.1:1/spec.json
                """, """
                openapi:
                  echo-api:
                    headers:
                      x-api-key: %s
                """.formatted(SECRET));
        // a frame named like the secret: the "unknown frame" problem echoes it
        Run run = run(project, "--env", "local", "--frames", SECRET);
        assertTrue(run.exit() == 2 || run.exit() == 4, run.err());
        assertTrue(run.err().contains("***"), run.err());
        assertFalse(run.err().contains(SECRET), () -> "stderr leaked the secret: " + run.err());
    }

    @Test
    void aBrokenPrivateLayerDoesNotEchoItsSecret() throws IOException {
        Path project = project("""
                environments: [local]
                openapi:
                  echo-api:
                    base-url: http://127.0.0.1:1/spec.json
                """, """
                openapi:
                  echo-api:
                    headers:
                      x-api-key: "%s
                """.formatted(SECRET));
        Run run = run(project, "--env", "local");
        assertEquals(2, run.exit(), run.err());
        assertTrue(run.err().contains("sources.local.private.yaml: invalid YAML at line "), run.err());
        assertFalse(run.err().contains(SECRET), () -> "stderr leaked the secret: " + run.err());
    }

    // ------------------------------------------------------------------ helpers

    private record Run(int exit, String out, String err) {
    }

    private Path project(String sources, String privateLayer) throws IOException {
        Path dir = root.resolve("redaction-project");
        Path testframe = dir.resolve("testframe");
        Files.createDirectories(testframe.resolve("frames"));
        Files.writeString(testframe.resolve("sources.yaml"), sources);
        if (privateLayer != null) {
            Files.writeString(testframe.resolve("sources.local.private.yaml"), privateLayer);
        }
        return dir;
    }

    private static Run run(Path project, String... options) {
        String[] args = new String[options.length + 2];
        args[0] = Cli.COMMAND;
        args[1] = project.toString();
        System.arraycopy(options, 0, args, 2, options.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = Cli.main(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
}
