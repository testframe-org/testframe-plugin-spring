package com.testframe.plugin.engine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The single redactor every outgoing string passes through (docs/runner-protocol.md,
 * "Security"; docs/test-tour.md, "Redaction"): responses, NDJSON events, error messages and log
 * lines. Each secret is replaced by {@code ***}, matching its raw value, its JSON-escaped form and
 * its URL-encoded forms, longest first; secrets shorter than 4 characters are not
 * substring-matched. Structural rules apply whatever the secrets: the user-info of any URI is
 * written {@code ***@}, and the values of the headers {@code authorization},
 * {@code proxy-authorization}, {@code cookie} and {@code set-cookie} (any object key with that
 * name, case-insensitively) are written {@code ***}.
 */
public final class Redactor {

    public static final String MASK = "***";
    public static final int MIN_SUBSTRING = 4;
    public static final Set<String> SENSITIVE_HEADERS = Set.of("authorization", "proxy-authorization", "cookie", "set-cookie");

    private static final Pattern URI_USERINFO = Pattern.compile("([A-Za-z][A-Za-z0-9+.-]*://)[^/\\s@\"'?#<>]+@");
    private static final Redactor STRUCTURAL = new Redactor(List.of());

    private final List<String> needles;
    private final boolean hasShort;

    public Redactor(Collection<String> secrets) {
        Set<String> forms = new LinkedHashSet<>();
        boolean shortOne = false;
        for (String secret : secrets == null ? List.<String>of() : secrets) {
            if (secret == null || secret.isEmpty()) {
                continue;
            }
            if (secret.length() < MIN_SUBSTRING) {
                shortOne = true;
                continue;
            }
            forms.add(secret);
            forms.add(jsonEscaped(secret));
            String form = URLEncoder.encode(secret, StandardCharsets.UTF_8);
            forms.add(form);
            forms.add(form.replace("+", "%20"));
        }
        List<String> sorted = new ArrayList<>(forms);
        sorted.removeIf(s -> s.length() < MIN_SUBSTRING);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        this.needles = List.copyOf(sorted);
        this.hasShort = shortOne;
    }

    /** Only the structural rules (no request secrets): for logs outside a run. */
    public static Redactor structural() {
        return STRUCTURAL;
    }

    /** Whether some secret was too short to be substring-redacted. */
    public boolean hasShortSecret() {
        return hasShort;
    }

    /** The redacted text (null stays null). */
    public String apply(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (String needle : needles) {
            if (out.contains(needle)) {
                out = out.replace(needle, MASK);
            }
        }
        return URI_USERINFO.matcher(out).replaceAll("$1" + MASK + "@");
    }

    /** {@code value} as a JSON tree with every string (keys included) redacted. */
    public JsonNode tree(Object value, ObjectMapper json) {
        JsonNode node = value instanceof JsonNode n ? n : json.valueToTree(value);
        return redact(node);
    }

    private JsonNode redact(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isString()) {
            return StringNode.valueOf(apply(node.asString()));
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> out.add(redact(item)));
            return out;
        }
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                String key = apply(e.getKey());
                JsonNode child = e.getValue();
                if (SENSITIVE_HEADERS.contains(e.getKey().toLowerCase(Locale.ROOT)) && child.isString()) {
                    out.put(key, MASK);
                } else {
                    out.set(key, redact(child));
                }
            }
            return out;
        }
        return node;
    }

    private static String jsonEscaped(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
