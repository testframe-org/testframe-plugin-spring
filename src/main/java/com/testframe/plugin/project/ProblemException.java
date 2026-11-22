package com.testframe.plugin.project;

import java.util.List;

/**
 * A request or project that cannot run: {@code status} is the HTTP status of the tour API
 * (400 invalid, 404 unknown project/frame/environment, 409 duplicate run id) and
 * {@code problems} lists every reason (the CLI prints them and exits 2).
 */
public class ProblemException extends RuntimeException {

    private final int status;
    private final List<String> problems;

    public ProblemException(int status, List<String> problems) {
        super(problems.isEmpty() ? "invalid request" : String.join("; ", problems));
        this.status = status;
        this.problems = List.copyOf(problems);
    }

    public ProblemException(int status, String problem) {
        this(status, List.of(problem));
    }

    public int status() {
        return status;
    }

    public List<String> problems() {
        return problems;
    }
}
