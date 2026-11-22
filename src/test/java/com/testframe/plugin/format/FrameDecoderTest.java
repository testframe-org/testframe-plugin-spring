package com.testframe.plugin.format;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameDecoderTest {

    private static ProjectDir.ProjectFile file(String path, String content) {
        return new ProjectDir.ProjectFile(path, content);
    }

    private static String card(String title, String source, String code) {
        return "-------------------\n" + title + "\n" + source + "\n-------------------\n```json\n" + code + "\n```\n";
    }

    private static String flow(String... entries) {
        return "-------------------\n-------------------\n" + String.join("\n", entries) + "\n";
    }

    @Test
    void decodesStepsAndCardsInFlowOrder() {
        List<ProjectDir.ProjectFile> files = List.of(
                file("step-1/flow.md", flow("b-echo", "a-fetch")),
                file("step-1/a-fetch.md", card("title: Fetch", "source: rawvalue", "[]")),
                file("step-1/b-echo.md", card("title: Echo", "source: rawvalue", "{}")),
                file("step-3/flow.md", flow("c-finish")),
                file("step-3/c-finish.md", card("title: Finish", "source: rawvalue", "1")));
        FrameDecoder.DecodedFrame frame = FrameDecoder.decode("frame", files);
        assertEquals(2, frame.steps().size());
        assertEquals(1, frame.steps().get(0).number(), "steps are renumbered from 1");
        assertEquals(2, frame.steps().get(1).number());
        assertEquals(List.of("bEcho", "aFetch"),
                frame.steps().get(0).cards().stream().map(c -> c.card().varName()).toList());
        assertTrue(frame.errors().isEmpty());
    }

    @Test
    void cardsNotListedAreAppendedAndReported() {
        List<ProjectDir.ProjectFile> files = List.of(
                file("step-1/flow.md", flow("a-first")),
                file("step-1/a-first.md", card("title: First", "source: rawvalue", "1")),
                file("step-1/z-last.md", card("title: Last", "source: rawvalue", "2")));
        FrameDecoder.DecodedFrame frame = FrameDecoder.decode("frame", files);
        assertEquals(List.of("aFirst", "zLast"),
                frame.steps().get(0).cards().stream().map(c -> c.card().varName()).toList());
        assertTrue(frame.errors().stream().anyMatch(e -> e.contains("z-last.md: not listed in flow.md")));
    }

    @Test
    void missingFlowIsReported() {
        List<ProjectDir.ProjectFile> files = List.of(
                file("step-1/b-card.md", card("title: B", "source: rawvalue", "1")),
                file("step-1/a-card.md", card("title: A", "source: rawvalue", "2")));
        FrameDecoder.DecodedFrame frame = FrameDecoder.decode("frame", files);
        assertEquals(List.of("aCard", "bCard"), frame.steps().get(0).cards().stream().map(c -> c.card().varName()).toList());
        assertTrue(frame.errors().stream().anyMatch(e -> e.contains("flow.md is missing")));
    }

    @Test
    void duplicateVarNamesAreReported() {
        List<ProjectDir.ProjectFile> files = List.of(
                file("step-1/flow.md", flow("a-card")),
                file("step-2/flow.md", flow("a-card")),
                file("step-1/a-card.md", card("title: A", "source: rawvalue", "1")),
                file("step-2/a-card.md", card("title: A2", "source: rawvalue", "2")));
        FrameDecoder.DecodedFrame frame = FrameDecoder.decode("frame", files);
        assertTrue(frame.errors().stream().anyMatch(e -> e.contains("var-name \"aCard\" is used twice")));
        assertEquals(1, frame.cardProblems().size());
    }

    @Test
    void flowEntryWithoutACardFileIsReported() {
        List<ProjectDir.ProjectFile> files = List.of(
                file("step-1/flow.md", flow("missing-card")),
                file("step-1/a-card.md", card("title: A", "source: rawvalue", "1")));
        FrameDecoder.DecodedFrame frame = FrameDecoder.decode("frame", files);
        assertTrue(frame.errors().stream().anyMatch(e -> e.contains("missing-card.md is listed but the card file is missing")));
        assertEquals(1, frame.steps().get(0).cards().size());
    }
}