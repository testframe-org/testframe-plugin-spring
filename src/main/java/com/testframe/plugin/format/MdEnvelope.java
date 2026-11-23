package com.testframe.plugin.format;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read side of the envelope shared by every project file (agents/specs/project-format.md):
 * <pre>
 * -------------------
 * key: value
 * -------------------
 * body
 * </pre>
 * Card bodies are one fenced Markdown block ({@link #unfence}). The plugin never renders files.
 */
public final class MdEnvelope {

    public static final String FRAME_SEP = "-------------------";

    private static final Pattern FIELD = Pattern.compile("([A-Za-z0-9_-]+):\\s?(.*)");
    private static final Pattern FENCE_OPEN = Pattern.compile("(`{3,})[A-Za-z0-9_-]*");

    /** Frontmatter fields (in file order; a repeated key keeps its last value) and the body. */
    public record Parsed(Map<String, String> fields, String body) {
    }

    private MdEnvelope() {
    }

    /**
     * Frontmatter = the lines between the first two {@link #FRAME_SEP} lines; the body is
     * everything after the closing one, without trailing newlines.
     */
    public static Parsed parse(String md) {
        String[] lines = (md == null ? "" : md).split("\\r?\\n", -1);
        Map<String, String> fields = new LinkedHashMap<>();
        int i = 0;
        if (lines.length > 0 && lines[0].equals(FRAME_SEP)) {
            i = 1;
            while (i < lines.length && !lines[i].equals(FRAME_SEP)) {
                Matcher m = FIELD.matcher(lines[i]);
                if (m.matches()) {
                    fields.put(m.group(1), m.group(2));
                }
                i++;
            }
            i++; // closing separator
        }
        String body = i < lines.length ? String.join("\n", Arrays.copyOfRange(lines, i, lines.length)) : "";
        return new Parsed(fields, stripTrailingNewlines(body));
    }

    /** The code inside a fenced block (a body without a fence is returned as is). */
    public static String unfence(String body) {
        String text = stripTrailingNewlines(body == null ? "" : body);
        int firstBreak = text.indexOf('\n');
        if (firstBreak < 0) {
            return text;
        }
        Matcher open = FENCE_OPEN.matcher(text.substring(0, firstBreak));
        if (!open.matches() || !text.endsWith("\n" + open.group(1))) {
            return text;
        }
        int end = text.length() - open.group(1).length() - 1;
        return end <= firstBreak ? "" : text.substring(firstBreak + 1, end);
    }

    static String stripTrailingNewlines(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '\n') {
            end--;
        }
        return text.substring(0, end);
    }
}
