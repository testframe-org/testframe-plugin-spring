package com.testframe.plugin.format;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Read-only access to one project folder (the folder that contains {@code testframe/}). Every
 * path is validated before it is read (the read side of the IDE store's rules): names match a
 * character whitelist, {@code ..} is rejected, the resolved path must stay under the project's
 * {@code testframe/} folder, symbolic links are never followed, and only {@code .md} files under
 * {@code frames/} and the {@code sources*.yaml} layers at the {@code testframe/} root are read.
 * Cards' {@code .private.md} notes and {@code .testframebuild/} are never read.
 */
public final class ProjectDir {

    public static final String TESTFRAME = "testframe";
    public static final String FRAMES = "frames";
    /** Largest file the decoders read (a card or a layer); bigger files are reported, not read. */
    public static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    private static final Pattern KEBAB = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final Pattern LAYER = Pattern.compile("sources(\\.[A-Za-z0-9_-]+)*\\.yaml");

    /** One file: {@code path} relative to the folder it was listed from ('/' separators). */
    public record ProjectFile(String path, String content) {
    }

    private final Path root;
    private final Path testframe;

    private ProjectDir(Path root) {
        this.root = root;
        this.testframe = root.resolve(TESTFRAME);
    }

    /** The project at {@code dir}; IllegalArgumentException when it holds no {@code testframe/} folder. */
    public static ProjectDir at(Path dir) {
        Path abs = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(abs.resolve(TESTFRAME), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(dir + " is not a project (no " + TESTFRAME + "/ folder)");
        }
        return new ProjectDir(abs);
    }

    public Path root() {
        return root;
    }

    public String name() {
        return root.getFileName() == null ? root.toString() : root.getFileName().toString();
    }

    /** Whether {@code name} is a valid frame folder name (kebab-case). */
    public static boolean isFrameName(String name) {
        return name != null && KEBAB.matcher(name).matches();
    }

    /** Frame folder names under {@code frames/}, sorted by name (non-kebab folders are skipped). */
    public List<String> frameNames() throws IOException {
        Path frames = testframe.resolve(FRAMES);
        if (!Files.isDirectory(frames, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(frames)) {
            return list.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                    .map(p -> p.getFileName().toString())
                    .filter(ProjectDir::isFrameName)
                    .sorted()
                    .toList();
        }
    }

    public boolean hasFrame(String frame) {
        return isFrameName(frame) && Files.isDirectory(frameDir(frame), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * The {@code .md} files of one frame (paths relative to {@code frames/{frame}/}, sorted),
     * without {@code .private.md} notes. Unreadable entries are added to {@code errors}.
     */
    public List<ProjectFile> frameFiles(String frame, List<String> errors) throws IOException {
        Path dir = frameDir(frame);
        List<ProjectFile> files = new ArrayList<>();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return files;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(dir)) {
            paths = walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().endsWith(".private.md"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        for (Path p : paths) {
            String rel = dir.relativize(p).toString().replace('\\', '/');
            String content = read(p, rel, errors);
            if (content != null) {
                files.add(new ProjectFile(rel, content));
            }
        }
        return files;
    }

    /**
     * The {@code sources*.yaml} files at the {@code testframe/} root by file name (sorted). The
     * decoder validates the names ({@code sources.yaml}, {@code sources.private.yaml},
     * {@code sources.{env}.yaml}, {@code sources.{env}.private.yaml}).
     */
    public Map<String, String> sourceLayers(List<String> errors) throws IOException {
        Map<String, String> layers = new LinkedHashMap<>();
        try (Stream<Path> list = Files.list(testframe)) {
            for (Path p : list.sorted().toList()) {
                String name = p.getFileName().toString();
                if (!LAYER.matcher(name).matches() || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String content = read(p, name, errors);
                if (content != null) {
                    layers.put(name, content);
                }
            }
        }
        return layers;
    }

    private Path frameDir(String frame) {
        if (!isFrameName(frame)) {
            throw new IllegalArgumentException("invalid frame name: " + frame);
        }
        Path dir = testframe.resolve(FRAMES).resolve(frame).normalize();
        if (!dir.startsWith(testframe.resolve(FRAMES))) {
            throw new IllegalArgumentException("path outside the project: " + frame);
        }
        return dir;
    }

    private String read(Path file, String rel, List<String> errors) throws IOException {
        if (!file.normalize().startsWith(testframe)) {
            errors.add(rel + ": outside the project");
            return null;
        }
        if (Files.size(file) > MAX_FILE_BYTES) {
            errors.add(rel + ": larger than " + MAX_FILE_BYTES + " bytes, not read");
            return null;
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
