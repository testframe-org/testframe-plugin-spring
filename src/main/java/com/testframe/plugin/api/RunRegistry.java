package com.testframe.plugin.api;

import com.testframe.plugin.engine.RunControl;
import com.testframe.plugin.project.ProblemException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/** The runs in progress by their client-chosen {@code runId} (cancel, agents/specs/test-tour.md). */
@Component
public class RunRegistry {

    private final Map<String, RunControl> active = new ConcurrentHashMap<>();

    /** Registers {@code runId}; 409 when it is already in progress. */
    public RunControl register(String runId) {
        RunControl control = RunControl.withDeadline(null);
        RunControl previous = active.putIfAbsent(runId, control);
        if (previous != null) {
            throw new ProblemException(409, "run " + runId + " is already in progress");
        }
        return control;
    }

    public void remove(String runId) {
        active.remove(runId);
    }

    /** Cancels the run {@code runId}; false when no such run is in progress. */
    public boolean cancel(String runId) {
        RunControl control = active.get(runId);
        if (control == null) {
            return false;
        }
        control.cancel();
        return true;
    }
}