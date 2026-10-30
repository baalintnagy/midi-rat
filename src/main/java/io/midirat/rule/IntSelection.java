package io.midirat.rule;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.midirat.config.Value;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

public sealed interface IntSelection {
    boolean contains(int value);

    record Range(int min, int max) implements IntSelection {
        public Range {
            if (min > max) {
                throw new IllegalArgumentException("Reversed interval");
            }
        }

        @Override
        public boolean contains(int value) {
            return value >= min
                && value <= max;
        }
    }

    record Values(Set<Integer> values) implements IntSelection {
        public Values {
            values = Set.copyOf(values);
        }

        @Override
        public boolean contains(int value) {
            return values.contains(value);
        }
    }

    static Range range(Value value, int min, int max) {
        if (value.node() instanceof MappingNode) {
            Map<String, Value> map = value.mapping("min", "max");
            int lower = Value.required(map, "min", value)
                .integer(min, max);
            int upper = Value.required(map, "max", value)
                .integer(min, max);
            if (lower > upper) {
                throw value.error("Reversed interval");
            }
            return new Range(lower, upper);
        }
        int scalar = value.integer(min, max);
        return new Range(scalar, scalar);
    }

    static IntSelection decode(Value value, int min, int max, boolean listOnly) {
        if (value.node() instanceof SequenceNode) {
            if (value.list()
                .isEmpty()) {
                throw value.error("Empty selection");
            }
            return new Values(value.list()
                .stream()
                .map(v -> v.integer(min, max))
                .collect(Collectors.toSet()));
        }
        if (listOnly) {
            throw value.error("Expected integer list");
        }
        if (!(value.node() instanceof MappingNode)) {
            throw value.error("Expected integer list or interval");
        }
        return range(value, min, max);
    }
}
