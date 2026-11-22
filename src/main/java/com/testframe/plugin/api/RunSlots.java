package com.testframe.plugin.api;

import com.testframe.plugin.project.ProblemException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/** At most {@code testframe.max-concurrent-runs} runs (default 4) at a time; more answer 429. */
@Component
public class RunSlots {

    private final Semaphore slots;

    public RunSlots(@Value("${testframe.max-concurrent-runs:4}") int max) {
        this.slots = new Semaphore(Math.max(1, max));
    }

    public void acquire() {
        if (!slots.tryAcquire()) {
            throw new ProblemException(429, "too many runs in progress (testframe.max-concurrent-runs)");
        }
    }

    public void release() {
        slots.release();
    }
}
