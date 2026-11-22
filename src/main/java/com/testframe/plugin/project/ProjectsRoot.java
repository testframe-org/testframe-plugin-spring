package com.testframe.plugin.project;

import com.testframe.plugin.format.ProjectDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The folder holding the projects the tour API serves ({@code testframe.projects-dir}): each
 * sub-folder that contains {@code testframe/} is a project. Project names match
 * {@code [A-Za-z0-9._-]+} without {@code ..}; the resolved folder must stay under the root and
 * symbolic links are not followed.
 */
public final class ProjectsRoot {

    private static final Pattern PROJECT_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    private final Path root;

    public ProjectsRoot(Path root) {
        this.root = root == null ? null : root.toAbsolutePath().normalize();
    }

    /** Whether a projects dir is configured and exists (the {@code tour} capability). */
    public boolean available() {
        return root != null && Files.isDirectory(root);
    }

    /** Project folder names, sorted. */
    public List<String> projects() throws IOException {
        if (!available()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> list = Files.list(root)) {
            for (Path dir : list.sorted().toList()) {
                String name = dir.getFileName().toString();
                if (PROJECT_NAME.matcher(name).matches() && !name.contains("..")
                        && Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)
                        && Files.isDirectory(dir.resolve(ProjectDir.TESTFRAME), LinkOption.NOFOLLOW_LINKS)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** The project {@code name}; 400 for an invalid name, 404 when it does not exist. */
    public ProjectDir project(String name) {
        if (!available()) {
            throw new ProblemException(404, "no projects dir configured (testframe.projects-dir)");
        }
        if (name == null || !PROJECT_NAME.matcher(name).matches() || name.contains("..")) {
            throw new ProblemException(400, "invalid project name: " + name);
        }
        Path dir = root.resolve(name).normalize();
        if (!dir.startsWith(root) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(dir.resolve(ProjectDir.TESTFRAME), LinkOption.NOFOLLOW_LINKS)) {
            throw new ProblemException(404, "unknown project: " + name);
        }
        return ProjectDir.at(dir);
    }
}
