package com.testframe.plugin.format;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only decoder of the layered sources of a project (docs/project-format.md, "Sources"):
 * {@code sources.yaml} (environments + every source) ← {@code sources.private.yaml} ←
 * {@code sources.{env}.yaml} ← {@code sources.{env}.private.yaml}. {@link #decode} validates the
 * files; {@link #resolve} merges them for one environment (last wins, {@code null} removes,
 * headers by lower-case name), substitutes {@code {env}} and derives the secrets (every resolved
 * value whose winning layer is a {@code .private.yaml} file).
 */
public final class SourcesDecoder {

    public static final String BASE = "sources.yaml";
    public static final String BASE_PRIVATE = "sources.private.yaml";
    public static final List<String> TYPES = List.of("openapi", "mongo", "sql", "kafka", "grpc");
    public static final String BASE_URL = "base-url";
    public static final String HEADERS = "headers";

    private static final Pattern ENV_NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final Pattern SOURCE_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]*");
    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9-]+");
    private static final Pattern LAYER = Pattern.compile("sources\\.(.+?)(\\.private)?\\.yaml");
    private static final Pattern URI_USERINFO = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://([^/?#@]*)@");
    private static final Pattern SCHEME_TOKEN = Pattern.compile("^[A-Za-z][A-Za-z0-9._-]*\\s+(\\S.*)$");

    /** One source's fields as one layer writes them: a {@code null} value removes the field (or header). */
    public record SourceFields(boolean hasBaseUrl, String baseUrl, boolean hasHeaders, Map<String, String> headers) {
    }

    /** One layer file: {@code env} null for the common layers. */
    public record Layer(String file, String env, boolean isPrivate, Map<String, Map<String, SourceFields>> types) {
    }

    /** Every layer of a project plus the problems found (a project with problems cannot run). */
    public record Sources(List<String> environments, List<Layer> layers, Map<String, Set<String>> declared,
                          List<String> errors) {

        public boolean valid() {
            return errors.isEmpty();
        }

        public boolean hasEnvironment(String env) {
            return environments.contains(env);
        }

        /** Every declared source ref ({@code {type}/{name}}), in catalog then name order. */
        public List<String> refs() {
            List<String> refs = new ArrayList<>();
            declared.forEach((type, names) -> names.forEach(n -> refs.add(type + "/" + n)));
            return refs;
        }
    }

    /** One source resolved in an environment; {@code privateFields} names the fields from private layers. */
    public record ResolvedSource(String type, String name, String baseUrl, Map<String, String> headers,
                                 List<String> privateFields) {

        public String ref() {
            return type + "/" + name;
        }

        /** Configured for the environment: a non-empty resolved base-url. */
        public boolean configured() {
            return baseUrl != null && !baseUrl.isEmpty();
        }
    }

    /** The sources of one environment and the secrets of the resolution. */
    public record Resolution(String environment, Map<String, ResolvedSource> sources, List<String> secrets) {

        public boolean configured(String ref) {
            ResolvedSource s = sources.get(ref);
            return s != null && s.configured();
        }
    }

    private SourcesDecoder() {
    }

    // ------------------------------------------------------------------ decode

    /** Decodes and validates the layer files (file name → content). */
    public static Sources decode(Map<String, String> files) {
        List<String> errors = new ArrayList<>();
        List<String> environments = new ArrayList<>();
        Map<String, Set<String>> declared = new LinkedHashMap<>();
        TYPES.forEach(t -> declared.put(t, new java.util.TreeSet<>()));
        List<Layer> layers = new ArrayList<>();

        String base = files.get(BASE);
        if (base == null) {
            errors.add(BASE + ": missing (it declares the environments and every source)");
        } else {
            Layer layer = layer(BASE, null, false, base, environments, errors);
            if (layer != null) {
                layers.add(layer);
                layer.types().forEach((type, sources) -> declared.get(type).addAll(sources.keySet()));
            }
        }

        // the other layers, in resolution order per environment (validated against the base)
        Map<String, String> ordered = new TreeMap<>(files);
        for (Map.Entry<String, String> file : ordered.entrySet()) {
            String name = file.getKey();
            if (name.equals(BASE)) {
                continue;
            }
            String env = null;
            boolean isPrivate;
            if (name.equals(BASE_PRIVATE)) {
                isPrivate = true;
            } else {
                Matcher m = LAYER.matcher(name);
                if (!m.matches() || m.group(1).contains(".") || m.group(1).equals("private")) {
                    errors.add(name + ": unknown file name (sources.yaml, sources.private.yaml, sources.{env}.yaml, "
                            + "sources.{env}.private.yaml)");
                    continue;
                }
                env = m.group(1);
                isPrivate = m.group(2) != null;
                if (base != null && !environments.contains(env)) {
                    errors.add(name + ": \"" + env + "\" is not in the environments of " + BASE);
                    continue;
                }
            }
            Layer layer = layer(name, env, isPrivate, file.getValue(), null, errors);
            if (layer == null) {
                continue;
            }
            layer.types().forEach((type, sources) -> sources.keySet().forEach(source -> {
                if (!declared.get(type).contains(source)) {
                    errors.add(name + ": " + type + "/" + source + " is not declared in " + BASE);
                }
            }));
            layers.add(layer);
        }
        return new Sources(List.copyOf(environments), List.copyOf(layers), declared, errors);
    }

    /**
     * Parses one layer. {@code environments} is non-null only for the base file (which must
     * declare them); problems go to {@code errors} prefixed with the file name.
     */
    private static Layer layer(String file, String env, boolean isPrivate, String content, List<String> environments,
                               List<String> errors) {
        SafeYaml.Value root;
        try {
            root = SafeYaml.load(content);
        } catch (SafeYaml.YamlProblem e) {
            errors.add(file + ": " + e.getMessage());
            return null;
        }
        if (!(root instanceof SafeYaml.Mapping top)) {
            errors.add(file + ": the document must be a map (environments and source types)");
            return null;
        }
        Map<String, Map<String, SourceFields>> types = new LinkedHashMap<>();
        if (environments != null) {
            readEnvironments(file, top.entries().get("environments"), environments, errors);
        } else if (top.entries().containsKey("environments")) {
            errors.add(file + ": environments is declared only in " + BASE);
        }
        for (Map.Entry<String, SafeYaml.Value> entry : top.entries().entrySet()) {
            String type = entry.getKey();
            if (type.equals("environments")) {
                continue;
            }
            if (!TYPES.contains(type)) {
                errors.add(file + ": unknown top-level key \"" + type + "\" (environments, " + String.join(", ", TYPES) + ")");
                continue;
            }
            Map<String, SourceFields> sources = new TreeMap<>();
            SafeYaml.Value value = entry.getValue();
            if (value instanceof SafeYaml.Mapping map) {
                for (Map.Entry<String, SafeYaml.Value> source : map.entries().entrySet()) {
                    String where = file + ": " + type + "/" + source.getKey();
                    if (!SOURCE_NAME.matcher(source.getKey()).matches() || source.getKey().length() < 2) {
                        errors.add(where + ": invalid source name (" + SOURCE_NAME.pattern() + ", at least 2 characters)");
                        continue;
                    }
                    SourceFields fields = fields(where, type, source.getValue(), errors);
                    if (fields != null) {
                        sources.put(source.getKey(), fields);
                    }
                }
            } else if (!(value instanceof SafeYaml.Scalar s && s.isNull())) {
                errors.add(file + ": " + type + " must be a map of sources by name");
                continue;
            }
            types.put(type, sources);
        }
        return new Layer(file, env, isPrivate, types);
    }

    private static void readEnvironments(String file, SafeYaml.Value value, List<String> environments, List<String> errors) {
        if (value == null) {
            errors.add(file + ": environments is required (a non-empty list of environment names)");
            return;
        }
        if (!(value instanceof SafeYaml.Sequence list) || list.items().isEmpty()) {
            errors.add(file + ": environments must be a non-empty list of environment names");
            return;
        }
        for (SafeYaml.Value item : list.items()) {
            if (!(item instanceof SafeYaml.Scalar s) || !s.isString()) {
                errors.add(file + ": environments: every entry must be a name");
                continue;
            }
            String env = s.text();
            if (!ENV_NAME.matcher(env).matches() || env.equals("private")) {
                errors.add(file + ": environments: \"" + env + "\" is not a valid environment name (kebab-case, not \"private\")");
            } else if (environments.contains(env)) {
                errors.add(file + ": environments: \"" + env + "\" is listed twice");
            } else {
                environments.add(env);
            }
        }
    }

    private static SourceFields fields(String where, String type, SafeYaml.Value value, List<String> errors) {
        if (value instanceof SafeYaml.Scalar s && s.isNull()) {
            return new SourceFields(false, null, false, Map.of());
        }
        if (!(value instanceof SafeYaml.Mapping map)) {
            errors.add(where + ": a source must be a map of fields");
            return null;
        }
        boolean ok = true;
        boolean hasBaseUrl = false;
        String baseUrl = null;
        boolean hasHeaders = false;
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, SafeYaml.Value> field : map.entries().entrySet()) {
            String key = field.getKey();
            if (key.equals(BASE_URL)) {
                hasBaseUrl = true;
                String text = stringOrNull(where + ": " + BASE_URL, field.getValue(), errors);
                if (text == STRING_ERROR) {
                    ok = false;
                } else {
                    baseUrl = text;
                }
            } else if (key.equals(HEADERS) && type.equals("openapi")) {
                hasHeaders = true;
                SafeYaml.Value h = field.getValue();
                if (h instanceof SafeYaml.Scalar s && s.isNull()) {
                    headers = null; // removes every header of the lower layers
                    continue;
                }
                if (!(h instanceof SafeYaml.Mapping hm)) {
                    errors.add(where + ": headers must be a map (header name → value)");
                    ok = false;
                    continue;
                }
                Set<String> seen = new LinkedHashSet<>();
                for (Map.Entry<String, SafeYaml.Value> header : hm.entries().entrySet()) {
                    String name = header.getKey();
                    if (!HEADER_NAME.matcher(name).matches()) {
                        errors.add(where + ": headers: \"" + name + "\" is not a header name ([A-Za-z0-9-]+)");
                        ok = false;
                        continue;
                    }
                    if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                        errors.add(where + ": headers: \"" + name + "\" is repeated (names ignore case)");
                        ok = false;
                        continue;
                    }
                    String text = stringOrNull(where + ": headers." + name, header.getValue(), errors);
                    if (text == STRING_ERROR) {
                        ok = false;
                    } else {
                        headers.put(name.toLowerCase(Locale.ROOT), text);
                    }
                }
            } else {
                errors.add(where + ": unknown field \"" + key + "\" (" + (type.equals("openapi") ? "base-url, headers" : "base-url") + ")");
                ok = false;
            }
        }
        return ok ? new SourceFields(hasBaseUrl, baseUrl, hasHeaders, headers) : null;
    }

    private static final String STRING_ERROR = new String("<error>");

    private static String stringOrNull(String where, SafeYaml.Value value, List<String> errors) {
        if (value instanceof SafeYaml.Scalar s) {
            if (s.isNull()) {
                return null;
            }
            if (s.isString()) {
                return s.text();
            }
            errors.add(where + ": must be a string (quote it)");
            return STRING_ERROR;
        }
        errors.add(where + ": must be a string");
        return STRING_ERROR;
    }

    // ----------------------------------------------------------------- resolve

    /** A resolved value and whether its winning layer is private. */
    private record Field(String value, boolean fromPrivate) {
    }

    /**
     * Resolves every declared source in {@code env}. The sources must be {@link Sources#valid()}
     * and {@code env} one of its environments (IllegalArgumentException otherwise).
     */
    public static Resolution resolve(Sources sources, String env) {
        if (!sources.valid()) {
            throw new IllegalArgumentException("the sources files are invalid");
        }
        if (!sources.hasEnvironment(env)) {
            throw new IllegalArgumentException("unknown environment \"" + env + "\"");
        }
        List<Layer> order = new ArrayList<>();
        for (String[] pick : new String[][]{{null, "false"}, {null, "true"}, {env, "false"}, {env, "true"}}) {
            for (Layer layer : sources.layers()) {
                boolean sameEnv = pick[0] == null ? layer.env() == null : pick[0].equals(layer.env());
                if (sameEnv && layer.isPrivate() == Boolean.parseBoolean(pick[1])) {
                    order.add(layer);
                }
            }
        }

        Map<String, ResolvedSource> resolved = new LinkedHashMap<>();
        Set<String> secrets = new LinkedHashSet<>();
        sources.declared().forEach((type, names) -> {
            for (String name : names) {
                Field baseUrl = null;
                Map<String, Field> headers = new TreeMap<>();
                for (Layer layer : order) {
                    SourceFields f = layer.types().getOrDefault(type, Map.of()).get(name);
                    if (f == null) {
                        continue;
                    }
                    if (f.hasBaseUrl()) {
                        baseUrl = f.baseUrl() == null ? null : new Field(f.baseUrl(), layer.isPrivate());
                    }
                    if (f.hasHeaders()) {
                        if (f.headers() == null) {
                            headers.clear();
                        } else {
                            for (Map.Entry<String, String> h : f.headers().entrySet()) {
                                if (h.getValue() == null) {
                                    headers.remove(h.getKey());
                                } else {
                                    headers.put(h.getKey(), new Field(h.getValue(), layer.isPrivate()));
                                }
                            }
                        }
                    }
                }
                List<String> privateFields = new ArrayList<>();
                String url = baseUrl == null ? null : substitute(baseUrl.value(), env);
                if (baseUrl != null && baseUrl.fromPrivate()) {
                    privateFields.add(BASE_URL);
                    secrets.addAll(secretsOf(url, false));
                }
                Map<String, String> headerValues = new LinkedHashMap<>();
                for (Map.Entry<String, Field> h : headers.entrySet()) {
                    String value = substitute(h.getValue().value(), env);
                    headerValues.put(h.getKey(), value);
                    if (h.getValue().fromPrivate()) {
                        privateFields.add(HEADERS + "." + h.getKey());
                        secrets.addAll(secretsOf(value, true));
                    }
                }
                resolved.put(type + "/" + name, new ResolvedSource(type, name, url, headerValues, List.copyOf(privateFields)));
            }
        });
        return new Resolution(env, resolved, List.copyOf(secrets));
    }

    /** Replaces the exact token {@code {env}} (other braces are left as written). */
    public static String substitute(String value, String env) {
        return value == null ? null : value.replace("{env}", env);
    }

    /**
     * The secrets of one private value (docs/runner-protocol.md, "Secrets"): the whole value;
     * for a URI with user-info also the password and {@code user:password} (decoded forms too);
     * for a {@code {scheme} {token}} header value also the token.
     */
    public static List<String> secretsOf(String value, boolean header) {
        Set<String> out = new LinkedHashSet<>();
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        out.add(value);
        Matcher uri = URI_USERINFO.matcher(value);
        if (uri.find()) {
            String userInfo = uri.group(1);
            int colon = userInfo.indexOf(':');
            if (colon >= 0) {
                String password = userInfo.substring(colon + 1);
                out.add(userInfo);
                out.add(password);
                String decoded = decode(password);
                if (!decoded.equals(password)) {
                    out.add(decoded);
                    out.add(decode(userInfo));
                }
            }
        }
        if (header) {
            Matcher token = SCHEME_TOKEN.matcher(value);
            if (token.matches()) {
                out.add(token.group(1).trim());
            }
        }
        out.removeIf(String::isEmpty);
        return List.copyOf(out);
    }

    private static String decode(String text) {
        try {
            return URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return text;
        }
    }
}
