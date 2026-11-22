package com.testframe.plugin.engine;

import java.util.concurrent.Future;

/**
 * The deadline and cancellation of one run. {@link #cancel()} aborts the card in progress (its
 * result is abandoned) and stops the run; the deadline aborts the running card as an error
 * ({@code run deadline exceeded}).
 */
public final class RunControl {

    private final long deadlineNanos;
    private volatile boolean cancelled;
    private volatile Future<?> current;

    private RunControl(long deadlineMs) {
        this.deadlineNanos = System.nanoTime() + Math.max(0, deadlineMs) * 1_000_000L;
    }

    /** A run that must end within {@code deadlineMs} (clamped to {@link Limits#MAX_RUN_MS}; null = the maximum). */
    public static RunControl withDeadline(Long deadlineMs) {
        long ms = deadlineMs == null || deadlineMs <= 0 ? Limits.MAX_RUN_MS : Math.min(deadlineMs, Limits.MAX_RUN_MS);
        return new RunControl(ms);
    }

    public long remainingMs() {
        return Math.max(0, (deadlineNanos - System.nanoTime()) / 1_000_000L);
    }

    public boolean deadlineReached() {
        return System.nanoTime() >= deadlineNanos;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** Stops the run: the card in progress is interrupted and abandoned. */
    public void cancel() {
        cancelled = true;
        Future<?> running = current;
        if (running != null) {
            running.cancel(true);
        }
    }

    void running(Future<?> future) {
        this.current = future;
        if (cancelled && future != null) {
            future.cancel(true);
        }
    }
}
