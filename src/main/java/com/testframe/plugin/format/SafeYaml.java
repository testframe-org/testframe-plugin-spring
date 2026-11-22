package com.testframe.plugin.format;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.CollectionStartEvent;
import org.yaml.snakeyaml.events.DocumentStartEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.MappingEndEvent;
import org.yaml.snakeyaml.events.MappingStartEvent;
import org.yaml.snakeyaml.events.NodeEvent;
import org.yaml.snakeyaml.events.ScalarEvent;
import org.yaml.snakeyaml.events.SequenceEndEvent;
import org.yaml.snakeyaml.events.SequenceStartEvent;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The restricted YAML loader of the {@code sources*.yaml} layers (docs/project-format.md,
 * "Sources"): maps, lists and scalars only, typed with the YAML 1.2 core schema; anchors,
 * aliases, explicit tags, duplicate keys, complex keys and multiple documents are errors. It
 * works on the parser's event stream, so no object is ever constructed from the document.
 */
public final class SafeYaml {

    /** A loaded value: {@link Mapping}, {@link Sequence} or {@link Scalar}. */
    public sealed interface Value permits Mapping, Sequence, Scalar {
    }

    /** A map with string keys, in document order. */
    public record Mapping(Map<String, Value> entries) implements Value {
    }

    public record Sequence(List<Value> items) implements Value {
    }

    /**
     * A scalar: {@code kind} is {@code string}, {@code null}, {@code bool}, {@code int} or
     * {@code float} (YAML 1.2 core schema; quoted scalars are always strings).
     */
    public record Scalar(String kind, String text) implements Value {

        public boolean isString() {
            return "string".equals(kind);
        }

        public boolean isNull() {
            return "null".equals(kind);
        }
    }

    /** A document that is not acceptable; the message names the line. */
    public static class YamlProblem extends RuntimeException {
        public YamlProblem(String message) {
            super(message);
        }
    }

    private static final Pattern NULL = Pattern.compile("~|null|Null|NULL|");
    private static final Pattern BOOL = Pattern.compile("true|True|TRUE|false|False|FALSE");
    private static final Pattern INT = Pattern.compile("[-+]?[0-9]+|0o[0-7]+|0x[0-9a-fA-F]+");
    private static final Pattern FLOAT = Pattern.compile(
            "[-+]?(\\.[0-9]+|[0-9]+(\\.[0-9]*)?)([eE][-+]?[0-9]+)?|[-+]?\\.(inf|Inf|INF)|\\.nan|\\.NaN|\\.NAN");

    private SafeYaml() {
    }

    /** Loads one document; an empty document is an empty mapping. */
    public static Value load(String text) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(20);
        options.setCodePointLimit((int) ProjectDir.MAX_FILE_BYTES);
        Yaml yaml = new Yaml(options);
        List<Event> events = new ArrayList<>();
        try {
            for (Event e : yaml.parse(new StringReader(text == null ? "" : text))) {
                events.add(e);
            }
        } catch (YAMLException e) {
            throw new YamlProblem("invalid YAML: " + oneLine(e.getMessage()));
        }
        long documents = events.stream().filter(e -> e instanceof DocumentStartEvent).count();
        if (documents > 1) {
            throw new YamlProblem("multiple YAML documents are not allowed");
        }
        Iterator<Event> it = events.stream()
                .filter(e -> e instanceof NodeEvent || e instanceof MappingEndEvent || e instanceof SequenceEndEvent)
                .iterator();
        if (!it.hasNext()) {
            return new Mapping(new LinkedHashMap<>());
        }
        return read(it.next(), it);
    }

    private static Value read(Event event, Iterator<Event> it) {
        if (event instanceof AliasEvent) {
            throw new YamlProblem(at(event) + "aliases are not allowed");
        }
        if (event instanceof NodeEvent node && node.getAnchor() != null) {
            throw new YamlProblem(at(event) + "anchors are not allowed");
        }
        if (event instanceof ScalarEvent scalar) {
            if (scalar.getTag() != null) {
                throw new YamlProblem(at(event) + "tags are not allowed");
            }
            return scalarOf(scalar);
        }
        if (event instanceof CollectionStartEvent collection && collection.getTag() != null) {
            throw new YamlProblem(at(event) + "tags are not allowed");
        }
        if (event instanceof MappingStartEvent) {
            Map<String, Value> entries = new LinkedHashMap<>();
            while (true) {
                Event key = next(it);
                if (key instanceof MappingEndEvent) {
                    return new Mapping(entries);
                }
                if (!(key instanceof ScalarEvent keyScalar)) {
                    throw new YamlProblem(at(key) + "keys must be plain names");
                }
                Value keyValue = read(key, it);
                String name = keyScalar.getValue();
                if (keyValue instanceof Scalar s && s.isNull() && name.isEmpty()) {
                    throw new YamlProblem(at(key) + "empty key");
                }
                if (entries.containsKey(name)) {
                    throw new YamlProblem(at(key) + "duplicate key \"" + name + "\"");
                }
                entries.put(name, read(next(it), it));
            }
        }
        if (event instanceof SequenceStartEvent) {
            List<Value> items = new ArrayList<>();
            while (true) {
                Event item = next(it);
                if (item instanceof SequenceEndEvent) {
                    return new Sequence(items);
                }
                items.add(read(item, it));
            }
        }
        throw new YamlProblem(at(event) + "unexpected YAML content");
    }

    private static Scalar scalarOf(ScalarEvent scalar) {
        String text = scalar.getValue();
        if (scalar.getScalarStyle() != DumperOptions.ScalarStyle.PLAIN) {
            return new Scalar("string", text);
        }
        if (NULL.matcher(text).matches()) {
            return new Scalar("null", text);
        }
        if (BOOL.matcher(text).matches()) {
            return new Scalar("bool", text);
        }
        if (INT.matcher(text).matches()) {
            return new Scalar("int", text);
        }
        if (FLOAT.matcher(text).matches()) {
            return new Scalar("float", text);
        }
        return new Scalar("string", text);
    }

    private static Event next(Iterator<Event> it) {
        if (!it.hasNext()) {
            throw new YamlProblem("unexpected end of YAML");
        }
        return it.next();
    }

    private static String at(Event event) {
        Mark mark = event.getStartMark();
        return mark == null ? "" : "line " + (mark.getLine() + 1) + ": ";
    }

    private static String oneLine(String message) {
        return message == null ? "" : message.replaceAll("\\s+", " ").trim();
    }
}
