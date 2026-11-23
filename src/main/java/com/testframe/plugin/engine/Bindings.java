package com.testframe.plugin.engine;

import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code {{varName.path}}} bindings between cards (agents/specs/project-format.md, "Bindings"), resolved
 * against the variables published so far. A string that is exactly one binding becomes the value
 * itself (keeping its JSON type); bindings inside longer text are replaced inline, non-string
 * values JSON-encoded. Paths that resolve to nothing keep the binding as written. Every resolved
 * binding text is recorded (the evidence's {@code detail.bindings}).
 */
public final class Bindings {

    /** Marker for "resolves to nothing": the binding stays as written. */
    public static final Object NOT_FOUND = new Object();

    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*\\}\\}");
    private static final Pattern WHOLE = Pattern.compile("^\\{\\{\\s*([\\w.]+)\\s*\\}\\}$");

    private Bindings() {
    }

    public static boolean hasBindings(String text) {
        return text != null && TEMPLATE.matcher(text).find();
    }

    /** Walks a parsed JSON value, replacing every binding; {@code seen} receives binding text → value. */
    public static Object substitute(Object input, Map<String, Object> vars, ObjectMapper json, Map<String, Object> seen) {
        if (input instanceof String text) {
            Matcher whole = WHOLE.matcher(text);
            if (whole.matches()) {
                Object value = resolvePath(whole.group(1), vars);
                if (value == NOT_FOUND) {
                    return text;
                }
                seen.put(text, value);
                return value;
            }
            Matcher m = TEMPLATE.matcher(text);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                Object value = resolvePath(m.group(1), vars);
                String replacement;
                if (value == NOT_FOUND) {
                    replacement = m.group();
                } else {
                    seen.put(m.group(), value);
                    replacement = value instanceof String s ? s : value == null ? "null" : json.writeValueAsString(value);
                }
                m.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(out);
            return out.toString();
        }
        if (input instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(substitute(item, vars, json, seen));
            }
            return out;
        }
        if (input instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), substitute(e.getValue(), vars, json, seen));
            }
            return out;
        }
        return input;
    }

    /** Follows a dot path through the published variables; {@link #NOT_FOUND} when nothing lives there. */
    static Object resolvePath(String path, Map<String, Object> vars) {
        String[] segments = path.split("\\.", -1);
        if (!vars.containsKey(segments[0])) {
            return NOT_FOUND;
        }
        Object value = vars.get(segments[0]);
        for (int i = 1; i < segments.length; i++) {
            if (value == null) {
                return NOT_FOUND;
            }
            String segment = segments[i];
            if (value instanceof List<?> list) {
                Integer index = index(segment);
                if (index == null || index < 0 || index >= list.size()) {
                    return NOT_FOUND;
                }
                value = list.get(index);
            } else if (value instanceof Map<?, ?> map) {
                if (!map.containsKey(segment)) {
                    return NOT_FOUND;
                }
                value = map.get(segment);
            } else {
                return NOT_FOUND;
            }
        }
        return value;
    }

    private static Integer index(String segment) {
        try {
            double d = Double.parseDouble(segment);
            if (d != Math.rint(d) || d > Integer.MAX_VALUE || d < Integer.MIN_VALUE) {
                return null;
            }
            return (int) d;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
