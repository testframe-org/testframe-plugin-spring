package com.testframe.plugin.format;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Naming rules of a project. Var-names are camelCase and live on disk as
 * their kebab-case file name ({@code getActiveProducts} ↔ {@code get-active-products.md}), so
 * a name is storable only when it survives that round trip (no consecutive capitals).
 */
public final class Names {

    private static final Pattern COMBINING_MARKS = Pattern.compile("[\\u0300-\\u036f]");
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");

    private Names() {
    }

    /** Lowercase kebab-case without accents, e.g. "Ação rápida" → "acao-rapida"; empty → "unnamed". */
    public static String slug(String text) {
        String s = Normalizer.normalize(String.valueOf(text).toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        s = COMBINING_MARKS.matcher(s).replaceAll("");
        s = NON_SLUG.matcher(s).replaceAll("-");
        s = trimChar(s, '-');
        return s.isEmpty() ? "unnamed" : s;
    }

    /** Variable name (camelCase) from a human title, e.g. "Get active products" → getActiveProducts. */
    public static String toVarName(String text) {
        StringBuilder out = new StringBuilder();
        for (String word : slug(text).split("-")) {
            if (word.isEmpty()) {
                continue;
            }
            out.append(out.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1));
        }
        return out.toString();
    }

    /** Kebab-case file name (without extension) of a var-name: getActiveProducts → get-active-products. */
    public static String varNameToFileName(String varName) {
        String s = String.valueOf(varName)
                .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .replaceAll("[^a-zA-Z0-9]+", "-")
                .toLowerCase(Locale.ROOT);
        s = trimChar(s, '-');
        return s.isEmpty() ? "card" : s;
    }

    /** Reverse of {@link #varNameToFileName}: get-active-products(.md) → getActiveProducts. */
    public static String fileNameToVarName(String fileName) {
        String base = fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
        return toVarName(base);
    }

    /** Coerces input into a var-name that survives the file-name round trip: "getHTTP data" → getHttpData. */
    public static String normalizeVarName(String input) {
        return fileNameToVarName(varNameToFileName(input));
    }

    public static boolean isRoundTrip(String name) {
        return normalizeVarName(name).equals(name);
    }



    /** {@code String.prototype.trim} semantics (JS whitespace and line terminators). */
    public static String trim(String text) {
        if (text == null) {
            return "";
        }
        int start = 0;
        int end = text.length();
        while (start < end && isJsWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && isJsWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    public static boolean isBlank(String text) {
        return trim(text).isEmpty();
    }

    private static boolean isJsWhitespace(char c) {
        return switch (c) {
            case '\t', '\n', '\u000B', '\f', '\r', ' ', ' ', ' ', ' ', ' ',
                 ' ', ' ', '　', '﻿' -> true;
            default -> c >= ' ' && c <= ' ';
        };
    }

    private static String trimChar(String s, char c) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == c) {
            start++;
        }
        while (end > start && s.charAt(end - 1) == c) {
            end--;
        }
        return s.substring(start, end);
    }
}
