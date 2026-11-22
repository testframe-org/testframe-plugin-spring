package com.testframe.plugin.model;

import java.util.List;

/** One step of a frame: cards in execution order (the step's {@code flow.md}). {@code number} labels only. */
public record Step(Integer number, List<Card> cards) {
}
