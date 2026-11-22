package com.testframe.plugin.format;

import io.github.jsonassert.AssertParser;
import io.github.jsonassert.JsonassertParser;
import io.github.jsonassert.JsonataException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The body of a {@code jsonassert} card, by subtype (spec: docs/project-format.md):
 * <ul>
 *   <li>{@code script}: one statement per line — {@code assert <expr> <op> <expr>} lines and/or
 *       plain expressions (the value of the last plain expression is the card's variable);</li>
 *   <li>{@code conditional}: exactly one boolean expression (no {@code assert}).</li>
 * </ul>
 * {@code //} lines are comments. Parsing goes through the jsonassert Java engine's parsers.
 */
public final class JsonassertCode {

    public static final String SCRIPT = "script";
    public static final String CONDITIONAL = "conditional";

    private static final Pattern ASSERT_HEAD = Pattern.compile("^assert\\b");

    private JsonassertCode() {
    }

    /** One statement of a body and its 1-based line within the card code. */
    public record Statement(int line, String text) {
    }

    /** The statements of a body: trimmed lines without blanks and {@code //} comments. */
    public static List<String> statements(String code) {
        return numbered(code).stream().map(Statement::text).toList();
    }

    /** {@link #statements} with their line numbers (the evidence's {@code line}). */
    public static List<Statement> numbered(String code) {
        List<Statement> out = new ArrayList<>();
        List<String> lines = (code == null ? "" : code).lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (!line.isEmpty() && !line.startsWith("//")) {
                out.add(new Statement(i + 1, line));
            }
        }
        return out;
    }

    public static boolean isAssert(String statement) {
        return ASSERT_HEAD.matcher(statement).find();
    }

    /** The code rule of a jsonassert body for {@code subtype}, or null when acceptable (empty = draft). */
    public static String problem(String subtype, String code) {
        List<String> statements = statements(code);
        if (statements.isEmpty()) {
            return null;
        }
        if (CONDITIONAL.equals(subtype)) {
            if (statements.size() != 1) {
                return "a conditional holds exactly one boolean expression";
            }
            String expression = statements.getFirst();
            if (isAssert(expression)) {
                return "a conditional is a bare boolean expression (no assert): " + expression;
            }
            return parseProblem(expression, false);
        }
        if (SCRIPT.equals(subtype)) {
            for (String statement : statements) {
                String problem = parseProblem(statement, isAssert(statement));
                if (problem != null) {
                    return problem;
                }
            }
        }
        return null;
    }

    private static String parseProblem(String statement, boolean assertLine) {
        try {
            if (assertLine) {
                new AssertParser().parse(statement);
            } else {
                new JsonassertParser().parse(statement);
            }
            return null;
        } catch (JsonataException | IllegalArgumentException e) {
            return "not a jsonassert " + (assertLine ? "assert line" : "expression") + ": " + statement
                    + " (" + e.getMessage() + ")";
        }
    }
}
