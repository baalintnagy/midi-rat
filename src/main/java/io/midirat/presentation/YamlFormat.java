package io.midirat.presentation;

import java.io.StringWriter;
import java.util.List;

import io.midirat.config.Value;
import org.yaml.snakeyaml.Yaml;

/** Preserve validated YAML fields for display without reconstructing rule semantics. */
public final class YamlFormat {
    private YamlFormat() {
    }

    public static List<String> lines(Value value) {
        StringWriter writer = new StringWriter();
        new Yaml().serialize(value.node(), writer);
        return writer.toString()
            .lines()
            .toList();
    }
}
