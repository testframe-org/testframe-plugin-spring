package com.testframe.plugin.format;

import com.testframe.plugin.model.Card;
import com.testframe.plugin.model.Step;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only decoder of one frame directory ({@code frames/{frame}/step-{n}/{var-file}.md} plus
 * each step's {@code flow.md}; spec: docs/project-format.md, "Layout" and "Step sequence").
 * Steps are renumbered from 1 in numeric order, cards follow the step's {@code flow.md}; cards it
 * does not list are appended by file name and reported, entries without a card file are reported.
 * Invalid card files are kept (best effort) with their problems, so a run reports them as that
 * card's error, and their problems are also in the frame's {@code errors}.
 */
public final class FrameDecoder {

    public static final String FLOW_FILE = "flow.md";

    private static final Pattern CARD_PATH = Pattern.compile("step-(\\d{1,9})/([a-z0-9][a-z0-9-]*)\\.md");
    private static final Pattern FLOW_PATH = Pattern.compile("step-(\\d{1,9})/" + Pattern.quote(FLOW_FILE));

    /** One decoded card: {@code problems} non-empty when its file is invalid. */
    public record DecodedCard(String fileBase, Card card, List<String> problems) {
    }

    /** One step, renumbered from 1. */
    public record DecodedStep(int number, List<DecodedCard> cards) {
    }

    /** One frame with every problem found while reading it. */
    public record DecodedFrame(String name, List<DecodedStep> steps, List<String> errors) {

        /** The frame as engine steps. */
        public List<Step> toSteps() {
            return steps.stream().map(s -> new Step(s.number(), s.cards().stream().map(DecodedCard::card).toList())).toList();
        }

        /** var-name → problems of the invalid cards (each fails when run). */
        public Map<String, String> cardProblems() {
            Map<String, String> out = new LinkedHashMap<>();
            for (DecodedStep step : steps) {
                for (DecodedCard c : step.cards()) {
                    if (!c.problems().isEmpty()) {
                        out.put(c.card().varName(), "invalid card file: " + String.join("; ", c.problems()));
                    }
                }
            }
            return out;
        }

        public List<DecodedCard> cards() {
            return steps.stream().flatMap(s -> s.cards().stream()).toList();
        }
    }

    private FrameDecoder() {
    }

    /** Decodes the files of frame {@code name} (paths relative to {@code frames/{name}/}). */
    public static DecodedFrame decode(String name, List<ProjectDir.ProjectFile> files) {
        List<String> errors = new ArrayList<>();
        Map<Integer, List<String>> flows = new HashMap<>();
        TreeMap<Integer, List<DecodedCard>> byStep = new TreeMap<>();
        for (ProjectDir.ProjectFile file : files) {
            String where = name + "/" + file.path();
            Matcher flow = FLOW_PATH.matcher(file.path());
            if (flow.matches()) {
                int number = Integer.parseInt(flow.group(1));
                flows.put(number, flowEntries(file.content()));
                byStep.computeIfAbsent(number, k -> new ArrayList<>());
                continue;
            }
            Matcher m = CARD_PATH.matcher(file.path());
            if (!m.matches()) {
                errors.add(where + ": path must be {frame}/step-{n}/{var-file}.md");
                continue;
            }
            String fileBase = m.group(2);
            CardDecoder.Decoded decoded = CardDecoder.decode(fileBase, file.content());
            List<String> problems = new ArrayList<>(decoded.errors());
            if (!Names.isRoundTrip(Names.fileNameToVarName(fileBase))
                    || !Names.varNameToFileName(Names.fileNameToVarName(fileBase)).equals(fileBase)) {
                problems.add("file name: \"" + fileBase + "\" is not the kebab-case of a camelCase var-name");
            }
            problems.forEach(p -> errors.add(where + ": " + p));
            byStep.computeIfAbsent(Integer.parseInt(m.group(1)), k -> new ArrayList<>())
                    .add(new DecodedCard(fileBase, decoded.card(), List.copyOf(problems)));
        }

        List<DecodedStep> steps = new ArrayList<>();
        Map<String, String> seen = new HashMap<>();
        for (Map.Entry<Integer, List<DecodedCard>> e : byStep.entrySet()) {
            List<DecodedCard> ordered = order(name, e.getKey(), e.getValue(), flows.get(e.getKey()), errors);
            for (DecodedCard c : ordered) {
                String previous = seen.put(c.card().varName(), "step-" + e.getKey());
                if (previous != null) {
                    errors.add("%s: var-name \"%s\" is used twice (%s and step-%d)".formatted(name, c.card().varName(),
                            previous, e.getKey()));
                }
            }
            steps.add(new DecodedStep(steps.size() + 1, ordered));
        }
        return new DecodedFrame(name, steps, errors);
    }

    /** The card file names listed in a {@code flow.md} body, in order (the {@code .md} extension is optional). */
    static List<String> flowEntries(String content) {
        List<String> entries = new ArrayList<>();
        for (String line : MdEnvelope.parse(content).body().split("\\R")) {
            String entry = line.trim();
            if (entry.endsWith(".md")) {
                entry = entry.substring(0, entry.length() - 3);
            }
            if (!entry.isEmpty()) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private static List<DecodedCard> order(String frame, int step, List<DecodedCard> cards, List<String> flow,
                                           List<String> errors) {
        List<DecodedCard> byName = cards.stream().sorted(Comparator.comparing(DecodedCard::fileBase)).toList();
        if (flow == null) {
            if (!cards.isEmpty()) {
                errors.add("%s/step-%d: %s is missing (cards in file-name order)".formatted(frame, step, FLOW_FILE));
            }
            return byName;
        }
        Map<String, DecodedCard> byBase = new HashMap<>();
        cards.forEach(c -> byBase.put(c.fileBase(), c));
        List<DecodedCard> ordered = new ArrayList<>();
        Set<String> listed = new HashSet<>();
        for (String entry : flow) {
            DecodedCard card = byBase.get(entry);
            if (card == null) {
                errors.add("%s/step-%d/%s: %s.md is listed but the card file is missing".formatted(frame, step, FLOW_FILE, entry));
                continue;
            }
            if (!listed.add(entry)) {
                errors.add("%s/step-%d/%s: %s.md is listed twice".formatted(frame, step, FLOW_FILE, entry));
                continue;
            }
            ordered.add(card);
        }
        for (DecodedCard c : byName) {
            if (!listed.contains(c.fileBase())) {
                errors.add("%s/step-%d/%s.md: not listed in %s".formatted(frame, step, c.fileBase(), FLOW_FILE));
                ordered.add(c);
            }
        }
        return ordered;
    }
}
