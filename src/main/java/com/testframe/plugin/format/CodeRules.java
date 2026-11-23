package com.testframe.plugin.format;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Code rules per (source type, subtype) (spec: agents/specs/project-format.md, "Code rules"). The
 * runner checks them before a card runs (a failing rule is that card's error). Empty code is a
 * draft and always acceptable; kinds without a rule accept anything.
 */
public final class CodeRules {

    private static final Pattern SQL_STATEMENT = Pattern.compile("^select\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_STRING = Pattern.compile("'(?:''|[^'])*'");

    private CodeRules() {
    }

    /** The problem with {@code code} for a card of (sourceType, subtype), or {@code null} when acceptable. */
    public static String problem(String sourceType, String subtype, String code) {
        String text = code == null ? "" : code;
        String sub = subtype == null ? "" : subtype;
        return switch (sourceType == null ? "" : sourceType) {
            case "mongo" -> MongoCall.problem(sub.isEmpty() ? null : sub, text);
            case "sql" -> "oracle".equals(sub) ? sqlProblem(stripLineComments(text, "--")) : null;
            case "jsonassert" -> JsonassertCode.problem(sub, text);
            default -> null;
        };
    }

    /** Every problem of the code (mongo: one per argument, e.g. {@code arg 3 (options): unknown key "foo"}). */
    public static List<String> problems(String sourceType, String subtype, String code) {
        if ("mongo".equals(sourceType)) {
            return MongoCall.problems(subtype == null || subtype.isEmpty() ? null : subtype, code);
        }
        String problem = problem(sourceType, subtype, code);
        return problem == null ? List.of() : List.of(problem);
    }


    private static String sqlProblem(String body) {
        if (body.isEmpty()) {
            return null;
        }
        if (!SQL_STATEMENT.matcher(body).find()) {
            return "oracle cards accept a single SELECT statement";
        }
        String statement = body.replaceAll(";\\s*$", "");
        if (SQL_STRING.matcher(statement).replaceAll("").contains(";")) {
            return "only one SELECT statement is allowed";
        }
        return null;
    }

    private static String stripLineComments(String code, String marker) {
        List<String> kept = new ArrayList<>();
        for (String line : code.split("\n", -1)) {
            if (!line.trim().startsWith(marker)) {
                kept.add(line);
            }
        }
        return String.join("\n", kept).trim();
    }
}
