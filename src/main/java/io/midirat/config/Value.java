package io.midirat.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.midirat.midi.MessageCodec;
import org.yaml.snakeyaml.DumperOptions.ScalarStyle;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/** Location-carrying YAML decoder. No arbitrary object construction or runtime maps. */
public record Value(Node node, String path) {
    public static Value read(Path file) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(40);
        options.setCodePointLimit(2_000_000);
        try (Reader reader = Files.newBufferedReader(file)) {
            Node root = new Yaml(new SafeConstructor(options)).compose(reader);
            if (root == null) {
                throw new IllegalArgumentException("Empty YAML: %s".formatted(file));
            }
            return new Value(root, file.toString());
        }
    }

    public Map<String, Value> mapping(String... allowed) {
        if (!(node instanceof MappingNode mapping)
            || !node.getTag()
                .equals(Tag.MAP)) {
            throw error("Expected mapping");
        }
        Set<String> keys = Set.of(allowed);
        Map<String, Value> result = new LinkedHashMap<>();
        for (NodeTuple tuple : mapping.getValue()) {
            String key = new Value(tuple.getKeyNode(), path).string();
            Value value = new Value(tuple.getValueNode(), "%s.%s".formatted(path, key));
            if (allowed.length > 0
                && !keys.contains(key)) {
                throw value.error("Unknown field");
            }
            if (result.putIfAbsent(key, value) != null) {
                throw value.error("Duplicate YAML key");
            }
        }
        return result;
    }

    public List<Value> list() {
        if (!(node instanceof SequenceNode sequence)
            || !node.getTag()
                .equals(Tag.SEQ)) {
            throw error("Expected list");
        }
        List<Value> result = new ArrayList<>();
        for (int i = 0; i < sequence.getValue()
            .size(); i++) {
            result.add(new Value(sequence.getValue()
                .get(i), "%s[%d]".formatted(path, i)));
        }
        return List.copyOf(result);
    }

    public String string() {
        if (!(node instanceof ScalarNode scalar)
            || !node.getTag()
                .equals(Tag.STR)
            || scalar.getValue()
                .isBlank()) {
            throw error("Expected nonblank string");
        }
        return scalar.getValue();
    }

    public int integer(int min, int max) {
        if (!(node instanceof ScalarNode scalar)
            || scalar.getScalarStyle() != ScalarStyle.PLAIN
            || !(node.getTag()
                .equals(Tag.INT)
                || node.getTag()
                    .equals(Tag.STR))) {
            throw error("Expected unquoted decimal or hexadecimal integer");
        }
        try {
            int result = MessageCodec.integer(scalar.getValue());
            if (result < min
                || result > max) {
                throw error("Integer outside %d..%d".formatted(min, max));
            }
            return result;
        } catch (ArithmeticException | IllegalArgumentException exception) {
            throw error("Integer overflow or invalid notation");
        }
    }

    public boolean bool() {
        if (!(node instanceof ScalarNode scalar)
            || !node.getTag()
                .equals(Tag.BOOL)
            || !(scalar.getValue()
                .equals("true")
                || scalar.getValue()
                    .equals("false"))) {
            throw error("Expected true or false");
        }
        return Boolean.parseBoolean(scalar.getValue());
    }

    public static Value required(Map<String, Value> map, String key, Value parent) {
        Value result = map.get(key);
        if (result == null) {
            throw parent.error("Missing %s".formatted(key));
        }
        return result;
    }

    public IllegalArgumentException error(String message) {
        return new IllegalArgumentException("%s at %s (line %d, column %d)".formatted(
            message, path, node.getStartMark()
                .getLine() + 1,
            node.getStartMark()
                .getColumn() + 1));
    }
}
