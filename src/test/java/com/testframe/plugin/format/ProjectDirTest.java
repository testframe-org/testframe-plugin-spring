package com.testframe.plugin.format;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The read-only project store is deny-by-default: a path is refused unless it is a real file this
 * project owns. Containment is decided on real paths, so a symbolic link — on the frame folder, on
 * a directory above the leaf, on {@code testframe/} itself, or on the leaf file — cannot move a
 * read outside the project. Also covered: the name whitelist (traversal, absolute path, encoded
 * separator, null byte), the file size cap, and the two things that are never read
 * ({@code .private.md} notes and {@code .testframebuild/}).
 */
class ProjectDirTest {

    private static final String OUTSIDE_SECRET = "text-that-must-never-be-read";

    @TempDir
    Path root;

    private Path project;
    private Path testframe;
    private Path outside;

    @BeforeEach
    void writeProject() throws IOException {
        project = root.resolve("my-project");
        testframe = project.resolve("testframe");
        Path frame = testframe.resolve("frames").resolve("active-products").resolve("step-1");
        Files.createDirectories(frame);
        Files.writeString(testframe.resolve("sources.yaml"), "environments: [local]\n");
        Files.writeString(frame.resolve("flow.md"), "-------------------\n-------------------\nget-a\n");
        Files.writeString(frame.resolve("get-a.md"), "-------------------\ntitle: Get A\n-------------------\n");
        Files.writeString(frame.resolve("get-a.private.md"), OUTSIDE_SECRET);
        Files.createDirectories(testframe.resolve(".testframebuild"));
        Files.writeString(testframe.resolve(".testframebuild").resolve("cached.md"), OUTSIDE_SECRET);

        outside = root.resolve("outside");
        Files.createDirectories(outside.resolve("secret-frame").resolve("step-1"));
        Files.writeString(outside.resolve("secret-frame").resolve("step-1").resolve("leak.md"), OUTSIDE_SECRET);
        Files.writeString(outside.resolve("secret.md"), OUTSIDE_SECRET);
        Files.writeString(outside.resolve("sources.local.private.yaml"), "openapi:\n  a:\n    base-url: http://h/s\n");
    }

    // ------------------------------------------------------------- the happy path

    @Test
    void readsTheFramesAndTheLayersOfAProject() throws IOException {
        ProjectDir dir = ProjectDir.at(project);
        assertEquals("my-project", dir.name());
        assertEquals(List.of("active-products"), dir.frameNames());
        assertTrue(dir.hasFrame("active-products"));

        List<String> errors = new ArrayList<>();
        List<ProjectDir.ProjectFile> files = dir.frameFiles("active-products", errors);
        assertEquals(List.of(), errors, errors.toString());
        assertEquals(List.of("step-1/flow.md", "step-1/get-a.md"), files.stream().map(ProjectDir.ProjectFile::path).toList());
        assertEquals(Map.of("sources.yaml", "environments: [local]\n"), dir.sourceLayers(errors));
    }

    @Test
    void aProjectReachedThroughASymlinkedParentDirectoryStillReads() throws IOException {
        // the legitimate side of the same rule: a symlink above the leaf is resolved, not refused
        Path link = root.resolve("projects-link");
        Files.createSymbolicLink(link, project.getParent());
        ProjectDir dir = ProjectDir.at(link.resolve(project.getFileName()));
        assertEquals(List.of("active-products"), dir.frameNames());
        List<String> errors = new ArrayList<>();
        assertEquals(2, dir.frameFiles("active-products", errors).size());
        assertEquals(List.of(), errors, errors.toString());
    }

    // ------------------------------------------------------ symbolic link escapes

    @Test
    void aFrameFolderThatIsASymlinkIsNotAFrame() throws IOException {
        Files.createSymbolicLink(testframe.resolve("frames").resolve("escaped"), outside.resolve("secret-frame"));
        ProjectDir dir = ProjectDir.at(project);

        assertFalse(dir.hasFrame("escaped"), "a symlinked frame folder is not a frame of this project");
        assertEquals(List.of("active-products"), dir.frameNames(), "a symlink is not listed as a frame folder");
        List<String> errors = new ArrayList<>();
        assertTrue(dir.frameFiles("escaped", errors).isEmpty());
        assertFalse(readAll(dir).contains(OUTSIDE_SECRET), "the target of the link was read");
    }

    @Test
    void aDirectoryAboveTheLeafThatIsASymlinkIsNotWalked() throws IOException {
        Path step = testframe.resolve("frames").resolve("active-products").resolve("step-1");
        Files.createSymbolicLink(step.resolve("nested"), outside.resolve("secret-frame").resolve("step-1"));
        ProjectDir dir = ProjectDir.at(project);

        List<String> errors = new ArrayList<>();
        List<String> paths = dir.frameFiles("active-products", errors).stream().map(ProjectDir.ProjectFile::path).toList();
        assertEquals(List.of("step-1/flow.md", "step-1/get-a.md"), paths, "nothing under the link is listed");
        assertEquals(List.of(), errors, errors.toString());
    }

    @Test
    void aFileThatIsASymlinkIsNeverRead() throws IOException {
        Path step = testframe.resolve("frames").resolve("active-products").resolve("step-1");
        Files.createSymbolicLink(step.resolve("outside.md"), outside.resolve("secret.md"));
        // a link that stays inside the project but leaves frames/ must not be read either
        Files.createSymbolicLink(step.resolve("layer.md"), testframe.resolve("sources.yaml"));
        ProjectDir dir = ProjectDir.at(project);

        List<String> errors = new ArrayList<>();
        List<String> paths = dir.frameFiles("active-products", errors).stream().map(ProjectDir.ProjectFile::path).toList();
        assertEquals(List.of("step-1/flow.md", "step-1/get-a.md"), paths, "symbolic links are never followed");
        assertFalse(readAll(dir).contains(OUTSIDE_SECRET));
    }

    @Test
    void aSymlinkedSourcesLayerIsNotRead() throws IOException {
        Files.createSymbolicLink(testframe.resolve("sources.local.private.yaml"),
                outside.resolve("sources.local.private.yaml"));
        ProjectDir dir = ProjectDir.at(project);

        List<String> errors = new ArrayList<>();
        assertEquals(List.of("sources.yaml"), List.copyOf(dir.sourceLayers(errors).keySet()),
                "only the real layers at the testframe/ root are read");
        assertEquals(List.of(), errors, errors.toString());
    }

    @Test
    void aSymlinkedFramesFolderIsNotADoorOutOfTheProject() throws IOException {
        // the whole frames/ folder is a link to somewhere else: every name below it looks textual
        // fine ("inside testframe/frames") and resolves to a real directory outside the project
        Path linked = root.resolve("linked-project");
        Files.createDirectories(linked.resolve("testframe"));
        Files.createSymbolicLink(linked.resolve("testframe").resolve("frames"), outside);
        ProjectDir dir = ProjectDir.at(linked);

        assertEquals(List.of(), dir.frameNames(), "frames/ itself is a link: there are no frames");
        assertFalse(dir.hasFrame("secret-frame"), "a frame reached through the link is not a frame");
        List<String> errors = new ArrayList<>();
        assertTrue(dir.frameFiles("secret-frame", errors).isEmpty(), "the linked folder was read");
        assertEquals(List.of(), errors, errors.toString());
    }

    @Test
    void aProjectWhoseTestframeFolderIsASymlinkIsNotAProject() throws IOException {
        Path other = root.resolve("other-project");
        Files.createDirectories(other);
        Files.createSymbolicLink(other.resolve("testframe"), testframe);
        assertThrows(IllegalArgumentException.class, () -> ProjectDir.at(other));
    }

    // ---------------------------------------------------------- the name whitelist

    @Test
    void onlyAKebabCaseNameInsideTheProjectIsAFrame() throws IOException {
        ProjectDir dir = ProjectDir.at(project);
        List<String> errors = new ArrayList<>();
        for (String name : List.of("..", "../..", "../../outside", "active-products/../../outside",
                "active-products/step-1", "/etc", "/outside/secret-frame", "a\u0000b", "active%2Dproducts",
                "Active-Products", "active_products", "", " ", ".", "active-products.")) {
            assertFalse(dir.hasFrame(name), () -> name + " was accepted as a frame name");
            assertTrue(dir.frameFiles(name, errors).isEmpty(), () -> name + " produced files");
        }
        assertEquals(List.of(), errors, errors.toString());
        assertFalse(readAll(dir).contains(OUTSIDE_SECRET));
    }

    @Test
    void aFolderThatIsNotKebabCaseIsSkippedNotRead() throws IOException {
        Files.createDirectories(testframe.resolve("frames").resolve("Bad_Name").resolve("step-1"));
        Files.writeString(testframe.resolve("frames").resolve("Bad_Name").resolve("step-1").resolve("x.md"),
                OUTSIDE_SECRET);
        ProjectDir dir = ProjectDir.at(project);

        assertEquals(List.of("active-products"), dir.frameNames(), "a non-kebab folder is skipped");
        assertFalse(dir.hasFrame("Bad_Name"));
        assertFalse(readAll(dir).contains(OUTSIDE_SECRET));
    }

    @Test
    void aFolderThatIsNotAProjectIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ProjectDir.at(outside));
        assertThrows(IllegalArgumentException.class, () -> ProjectDir.at(root.resolve("missing")));
    }

    // ------------------------------------------------------- what is never read

    @Test
    void privateNotesAndGeneratedFilesAreNeverRead() throws IOException {
        Files.writeString(testframe.resolve("sources.local.private.yaml"), "openapi:\n  a:\n    base-url: http://h/s\n");
        ProjectDir dir = ProjectDir.at(project);

        List<String> errors = new ArrayList<>();
        List<String> paths = dir.frameFiles("active-products", errors).stream().map(ProjectDir.ProjectFile::path).toList();
        assertFalse(paths.stream().anyMatch(p -> p.endsWith(".private.md")), paths.toString());
        assertFalse(paths.stream().anyMatch(p -> p.contains(".testframebuild")), paths.toString());
        assertEquals(List.of("sources.local.private.yaml", "sources.yaml"),
                List.copyOf(dir.sourceLayers(errors).keySet()).stream().sorted().toList());
    }

    @Test
    void aFileOverTheSizeCapIsReportedNotRead() throws IOException {
        Path step = testframe.resolve("frames").resolve("active-products").resolve("step-1");
        Files.writeString(step.resolve("huge.md"), "a".repeat((int) ProjectDir.MAX_FILE_BYTES + 1));
        ProjectDir dir = ProjectDir.at(project);

        List<String> errors = new ArrayList<>();
        List<String> paths = dir.frameFiles("active-products", errors).stream().map(ProjectDir.ProjectFile::path).toList();
        assertFalse(paths.contains("step-1/huge.md"), paths.toString());
        assertEquals(List.of("step-1/huge.md: larger than " + ProjectDir.MAX_FILE_BYTES + " bytes, not read"), errors);
    }

    @Test
    void aFrameFolderThatDoesNotExistIsNotAnError() throws IOException {
        ProjectDir dir = ProjectDir.at(project);
        assertFalse(dir.hasFrame("missing-frame"));
        List<String> errors = new ArrayList<>();
        assertTrue(dir.frameFiles("missing-frame", errors).isEmpty());
        assertEquals(List.of(), errors);
    }

    /** Every string the store hands out, to assert none of it came from outside the project. */
    private String readAll(ProjectDir dir) throws IOException {
        StringBuilder all = new StringBuilder();
        List<String> errors = new ArrayList<>();
        for (String frame : dir.frameNames()) {
            dir.frameFiles(frame, errors).forEach(f -> all.append(f.content()));
        }
        dir.sourceLayers(errors).forEach((name, content) -> all.append(name).append(content));
        return all.toString();
    }

    @Test
    void theContentOfAReadFileIsExactlyWhatIsOnDisk() throws IOException {
        ProjectDir dir = ProjectDir.at(project);
        List<String> errors = new ArrayList<>();
        Map<String, String> byPath = new LinkedHashMap<>();
        dir.frameFiles("active-products", errors).forEach(f -> byPath.put(f.path(), f.content()));
        assertEquals(Files.readString(testframe.resolve("frames").resolve("active-products").resolve("step-1")
                .resolve("get-a.md"), StandardCharsets.UTF_8), byPath.get("step-1/get-a.md"));
    }
}
