package io.midirat.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.nodes.MappingNode;

public record ApplicationConfig(Path file, String backend, Path modules, Path network,
    Map<String, PortBinding> inputs, Map<String, PortBinding> outputs) {
    public sealed interface PortBinding {
        record Name(String value) implements PortBinding {
        }

        record Id(String value) implements PortBinding {
        }
    }

    public ApplicationConfig {
        inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        outputs = Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
    }

    public static ApplicationConfig read(Path file) throws IOException {
        Path absolute = file.toAbsolutePath()
            .normalize();
        Value root = Value.read(absolute);
        Map<String, Value> top = root.mapping("schemaVersion", "application");
        Value.required(top, "schemaVersion", root)
            .integer(1, 1);
        Value app = Value.required(top, "application", root);
        Map<String, Value> f = app.mapping("backend", "moduleDirectory", "networkFile", "ports");
        String backend = Value.required(f, "backend", app)
            .string();
        if (!Set.of("winmm", "windows-midi-services", "java-sound")
            .contains(backend)) {
            throw app.error("Unsupported backend");
        }
        Path modules = absolute.getParent()
            .resolve(f.containsKey("moduleDirectory")
                ? f.get("moduleDirectory")
                    .string()
                : "./modules")
            .normalize();
        Path network = absolute.getParent()
            .resolve(
                Value.required(f, "networkFile", app)
                    .string())
            .normalize();
        Map<String, PortBinding> inputs = Map.of();
        Map<String, PortBinding> outputs = Map.of();
        if (f.containsKey("ports")) {
            Map<String, Value> ports = f.get("ports")
                .mapping("inputs", "outputs");
            inputs = bindings(ports.get("inputs"));
            outputs = bindings(ports.get("outputs"));
        }
        for (String name : inputs.keySet()) {
            if (outputs.containsKey(name)) {
                throw app.error("Input/output alias collision");
            }
        }
        return new ApplicationConfig(absolute, backend, modules, network, inputs, outputs);
    }

    private static Map<String, PortBinding> bindings(Value value) {
        if (value == null) {
            return Map.of();
        }
        Map<String, PortBinding> result = new LinkedHashMap<>();
        value.mapping()
            .forEach((alias, binding) -> {
                PortBinding selected;
                if (binding.node() instanceof MappingNode) {
                    Map<String, Value> id = binding.mapping("id");
                    selected = new PortBinding.Id(Value.required(id, "id", binding)
                        .string());
                } else {
                    selected = new PortBinding.Name(binding.string());
                }
                result.put(alias, selected);
            });
        return result;
    }
}
