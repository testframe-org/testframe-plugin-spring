package com.testframe.plugin.engine;

import com.testframe.plugin.model.Card;
import com.testframe.plugin.model.CardResult;

import java.time.Instant;

/** Receives the run's card events as they happen (NDJSON streams, CLI output). */
public interface RunListener {

    RunListener NONE = new RunListener() {
    };

    default void cardStarted(int step, Card card, Instant at) {
    }

    /** Every returned card, skipped ones included (they get no {@link #cardStarted}). */
    default void cardFinished(int step, CardResult result) {
    }
}
