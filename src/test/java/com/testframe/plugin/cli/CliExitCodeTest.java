package com.testframe.plugin.cli;

import com.testframe.plugin.Json;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The headless CLI's contract (agents/specs/test-tour.md, "Headless CLI"): exit 0 passed, 1 failed,
 * 2 bad invocation or project, 3 interrupted (covered by {@code CliInterruptionTest}), 4 internal
 * error — and {@code --out} never writes into the project the run reads, not even through a
 * symbolic link, because the plugin writes nothing to a project.
 */
class CliExitCodeTest {

    private static final String ENV = "local";

    @TempDir
    Path root;

    private Path project;
    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    @BeforeEach
    void writeProject() throws IOException {
        project = root.resolve("exit-project");
        Path testframe = project.resolve("testframe");
        Files.createDirectories(testframe);
        Files.writeString(testframe.resolve("sources.yaml"), "environments: [%s]\n".formatted(ENV));
        frame(testframe, "passing", "check-pass", "assert 1 == 1");
        frame(testframe, "failing", "check-fail", "assert 1 == 2");
    }

    // ---------------------------------------------------------------------- exit codes

    @Test
    void exit0WhenEverySelectedFramePasses() {
        assertEquals(0, run("run", project, "--env", ENV, "--frames", "passing"), err());
        assertTrue(out().contains("frame passing: passed (success 1, error 0, skipped 0, notRun 0)"), out());
    }

    @Test
    void exit1WhenACardFails() {
        assertEquals(1, run("run", project, "--env", ENV, "--frames", "failing"), err());
        assertTrue(out().contains("frame failing: failed (success 0, error 1, skipped 0, notRun 0)"), out());
    }

    @Test
    void exit1WhenAnyFrameFails() {
        assertEquals(1, run("run", project, "--env", ENV), err());
        assertTrue(out().contains("frame passing: passed"), out());
        assertTrue(out().contains("frame failing: failed"), out());
    }

    @Test
    void exit2ForABadInvocation() {
        assertEquals(2, run());
        assertTrue(err().contains("usage:"), err());
        assertEquals(2, run("run"));
        assertEquals(2, run("run", project));
        assertTrue(err().contains("--env is required"), err());
        assertEquals(2, run("run", project, "--env"));
        assertEquals(2, run("run", project, "--env", ENV, "--nope"));
        assertTrue(err().contains("unknown option --nope"), err());
        assertEquals(2, run("run", project, "--env", ENV, "unexpected"));
        assertEquals(2, run("run", project, "--env", ENV, "--frames"));
        assertEquals(2, run("run", project, "--env", ENV, "--frames", " , "));
        assertEquals(2, run("run", project, "--env", ENV, "--out", root.resolve("report.txt").toString()));
        assertTrue(err().contains("--out format follows the extension"), err());
        assertEquals(2, run("run", project, "--env", ENV, "--out"));
    }

    @Test
    void exit2ForAProjectOrFrameThatCannotRun() {
        assertEquals(2, run("run", root.resolve("no-such-project"), "--env", ENV));
        assertTrue(err().contains("is not a project"), err());

        assertEquals(2, run("run", project, "--env", ENV, "--frames", "no-such-frame"));
        assertTrue(err().contains("no-such-frame"), err());

        assertEquals(2, run("run", project, "--env", "hml", "--frames", "passing"));
        assertTrue(err().contains("unknown environment: hml"), err());
    }

    @Test
    void exit2ForAnInvalidSourcesLayer() throws IOException {
        Files.writeString(project.resolve("testframe").resolve("sources.yaml"), "environments: [local\n");
        assertEquals(2, run("run", project, "--env", ENV, "--frames", "passing"), err());
        assertTrue(err().contains("sources.yaml"), err());
    }

    @Test
    void exit4WhenTheProjectFilesCannotBeRead() throws IOException {
        Path testframe = project.resolve("testframe");
        Files.setPosixFilePermissions(testframe, PosixFilePermissions.fromString("---------"));
        try {
            Assumptions.assumeFalse(Files.isReadable(testframe),
                    "this user can read anything, so there is no permission to enforce");
            assertEquals(4, run("run", project, "--env", ENV), err());
            assertTrue(err().contains("cannot read the sources files"), err());
        } finally {
            Files.setPosixFilePermissions(testframe, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    // ------------------------------------------------------------------ --out placement

    @Test
    void exit2WhenTheReportWouldBeWrittenInsideTheProject() {
        for (Path target : List.of(project.resolve("report.json"),
                project.resolve("testframe").resolve("report.json"),
                project.resolve("testframe").resolve("frames").resolve("passing").resolve("junit.xml"))) {
            int exit = run("run", project, "--env", ENV, "--frames", "passing", "--out", target.toString());
            assertEquals(2, exit, () -> "--out " + target + " was accepted: " + err());
            assertTrue(err().contains("--out must not write inside the project"), err());
        }
        assertFalse(Files.exists(project.resolve("report.json")));
        assertFalse(Files.exists(project.resolve("testframe").resolve("report.json")));
        assertFalse(Files.exists(project.resolve("testframe").resolve("frames").resolve("passing").resolve("junit.xml")));
    }

    @Test
    void exit2WhenTheReportPathIsASymbolicLink() throws IOException {
        Path outside = root.resolve("outside.json");
        Path link = root.resolve("link.json");
        Files.createSymbolicLink(link, outside);

        assertEquals(2, run("run", project, "--env", ENV, "--frames", "passing", "--out", link.toString()));
        assertTrue(err().contains("--out must not be a symbolic link"), err());
        assertFalse(Files.exists(outside, LinkOption.NOFOLLOW_LINKS), "nothing was written through the link");
    }

    /** A link on a directory above the file is the same escape with one more hop. */
    @Test
    void exit2WhenADirectoryAboveTheReportPointsIntoTheProject() throws IOException {
        Path reports = root.resolve("reports");
        Files.createSymbolicLink(reports, project.resolve("testframe"));

        assertEquals(2, run("run", project, "--env", ENV, "--frames", "passing",
                "--out", reports.resolve("report.json").toString()));
        assertTrue(err().contains("--out must not write inside the project"), err());
        assertFalse(Files.exists(project.resolve("testframe").resolve("report.json")));
    }

    @Test
    void aReportOutsideTheProjectIsWrittenInBothFormats() throws Exception {
        Path json = root.resolve("report.json");
        Path xml = root.resolve("junit.xml");

        assertEquals(0, run("run", project, "--env", ENV, "--frames", "passing",
                "--out", json.toString(), "--out", xml.toString()), err());

        JsonNode report = Json.MAPPER.readTree(Files.readString(json));
        assertEquals("passed", report.path("result").asString());
        assertEquals("exit-project", report.path("project").asString());
        assertEquals(ENV, report.path("environment").asString());
        assertEquals("passing", report.path("frames").path(0).path("frame").asString());
        assertEquals(1, report.path("frames").path(0).path("counts").path("success").asInt());

        var suites = parse(Files.readString(xml)).getElementsByTagName("testsuite");
        assertEquals(1, suites.getLength());
    }

    // ------------------------------------------------------------------------ helpers

    private int run(Object... parts) {
        outBytes.reset();
        errBytes.reset();
        List<String> args = new ArrayList<>();
        for (Object part : parts) {
            args.add(part instanceof Path path ? path.toString() : String.valueOf(part));
        }
        return Cli.main(args.toArray(String[]::new), print(outBytes), print(errBytes));
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    private static PrintStream print(ByteArrayOutputStream bytes) {
        return new PrintStream(bytes, true, StandardCharsets.UTF_8);
    }

    private static void frame(Path testframe, String frame, String file, String code) throws IOException {
        Path step = testframe.resolve("frames").resolve(frame).resolve("step-1");
        Files.createDirectories(step);
        Files.writeString(step.resolve("flow.md"), """
                -------------------
                -------------------
                %s
                """.formatted(file));
        Files.writeString(step.resolve(file + ".md"), """
                -------------------
                title: Check
                source: jsonassert
                subtype: script
                -------------------
                ```jsonassert
                %s
                ```
                """.formatted(code));
    }

    private static org.w3c.dom.Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
