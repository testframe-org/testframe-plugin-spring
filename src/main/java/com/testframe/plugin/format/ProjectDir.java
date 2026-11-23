package com.testframe.plugin.format;

import java.io.IOException;
import java.io.InputStream;
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
 *
 * <p>Containment is decided on <b>real</b> paths, not on the path text: the project root is
 * resolved once with {@link Path#toRealPath()} and every folder and file is compared against that
 * real root, so a symbolic link on the project folder, on {@code testframe/}, on {@code frames/},
 * on a frame folder or on any directory above the leaf cannot move a read outside the project.
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
    private final Path realTestframe;

    private ProjectDir(Path root, Path realRoot) {
        this.root = root;
        this.testframe = root.resolve(TESTFRAME);
        this.realTestframe = realRoot.resolve(TESTFRAME);
    }

    /**
     * The project at {@code dir}; IllegalArgumentException when it holds no {@code testframe/}
     * folder or when the folder cannot be resolved to its real path.
     */
    public static ProjectDir at(Path dir) {
        Path abs = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(abs.resolve(TESTFRAME), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(dir + " is not a project (no " + TESTFRAME + "/ folder)");
        }
        try {
            return new ProjectDir(abs, abs.toRealPath());
        } catch (IOException e) {
            throw new IllegalArgumentException(dir + " cannot be resolved: " + e.getClass().getSimpleName(), e);
        }
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
        return realFrameDir(frame) != null;
    }

    /**
     * The {@code .md} files of one frame (paths relative to {@code frames/{frame}/}, sorted),
     * without {@code .private.md} notes. Unreadable entries are added to {@code errors}.
     */
    public List<ProjectFile> frameFiles(String frame, List<String> errors) throws IOException {
        List<ProjectFile> files = new ArrayList<>();
        Path dir = realFrameDir(frame);
        if (dir == null) {
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

    /**
     * The real folder of {@code frames/{frame}}, or null: the name must be kebab-case, the folder
     * must be a real directory (never a symbolic link), and its real path must stay inside this
     * project's real {@code frames/} — a symbolic link on the frame folder or on any directory
     * above it cannot move the read outside the project.
     */
    private Path realFrameDir(String frame) {
        if (!isFrameName(frame)) {
            return null;
        }
        Path dir = testframe.resolve(FRAMES).resolve(frame).normalize();
        if (!dir.startsWith(testframe.resolve(FRAMES)) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        return realInside(dir, realTestframe.resolve(FRAMES));
    }

    /**
     * The real path of {@code path} when it stays inside {@code realBase}; null otherwise (a
     * symbolic link anywhere on the path resolves to its target, so an escape is caught here).
     */
    private static Path realInside(Path path, Path realBase) {
        try {
            Path real = path.toRealPath();
            return real.startsWith(realBase) ? real : null;
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    /**
     * Reads one already-listed file: a symbolic link is never followed, the real path must stay
     * inside {@code testframe/}, and at most {@link #MAX_FILE_BYTES} are read (the stream is cut,
     * so a file that grows between listing and reading cannot be over-read).
     */
    private String read(Path file, String rel, List<String> errors) throws IOException {
        if (Files.isSymbolicLink(file) || realInside(file, realTestframe) == null) {
            errors.add(rel + ": outside the project");
            return null;
        }
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) {
            bytes = in.readNBytes((int) MAX_FILE_BYTES + 1);
        }
        if (bytes.length > MAX_FILE_BYTES) {
            errors.add(rel + ": larger than " + MAX_FILE_BYTES + " bytes, not read");
            return null;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
