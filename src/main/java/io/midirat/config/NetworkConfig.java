package io.midirat.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.midirat.api.ModuleInstance;
import io.midirat.api.ModuleProvider;
import io.midirat.midi.Endpoint;

public record NetworkConfig(Map<String, Endpoint> endpoints, List<Instance> instances) {
    public record Instance(String name, String type, List<Endpoint> sources,
        List<Endpoint> destinations, ModuleInstance module) {
        public Instance {
            sources = List.copyOf(sources);
            destinations = List.copyOf(destinations);
        }
    }

    public NetworkConfig {
        endpoints = Collections.unmodifiableMap(new LinkedHashMap<>(endpoints));
        instances = List.copyOf(instances);
    }

    public static NetworkConfig read(ApplicationConfig app,
        Map<String, ModuleProvider> providers) throws IOException {
        Value root = Value.read(app.network());
        Map<String, Value> top = root.mapping("schemaVersion", "network");
        Value.required(top, "schemaVersion", root)
            .integer(1, 1);
        Value network = Value.required(top, "network", root);
        Map<String, Value> f = network.mapping("buses", "modules");
        Map<String, Endpoint> endpoints = new LinkedHashMap<>();
        app.inputs()
            .keySet()
            .forEach(n -> endpoints.put(n, new Endpoint(n, Endpoint.Kind.INPUT)));
        app.outputs()
            .keySet()
            .forEach(n -> endpoints.put(n, new Endpoint(n, Endpoint.Kind.OUTPUT)));
        if (f.containsKey("buses")) {
            for (Value bus : f.get("buses")
                .list()) {
                String name = bus.string();
                if (endpoints.putIfAbsent(name, new Endpoint(name, Endpoint.Kind.BUS)) != null) {
                    throw bus.error("Duplicate endpoint name");
                }
            }
        }
        List<Instance> instances = new ArrayList<>();
        Set<String> names = new HashSet<>();
        try {
            for (Value module : Value.required(f, "modules", network)
                .list()) {
                Map<String, Value> m = module.mapping("name", "type", "sources", "destinations",
                    "settings");
                String name = Value.required(m, "name", module)
                    .string();
                if (!names.add(name)) {
                    throw module.error("Duplicate instance name");
                }
                String type = Value.required(m, "type", module)
                    .string();
                ModuleProvider provider = providers.get(type);
                if (provider == null) {
                    throw module.error("Unavailable module type: %s".formatted(type));
                }
                List<Endpoint> sources = resolve(m.get("sources"), endpoints, true);
                List<Endpoint> destinations = resolve(m.get("destinations"), endpoints, false);
                if (provider.role() == ModuleProvider.Role.EMITTER) {
                    if (m.containsKey("sources")) {
                        throw module.error("Emitter has no sources");
                    }
                } else if (sources.isEmpty()) {
                    throw module.error("Sources required");
                }
                if (provider.role() == ModuleProvider.Role.MONITOR) {
                    if (m.containsKey("destinations")) {
                        throw module.error("Monitor has no destinations");
                    }
                } else if (destinations.isEmpty()) {
                    throw module.error("Destinations required");
                }
                ModuleInstance prepared = provider.prepare(
                    Value.required(m, "settings", module), sources);
                instances.add(new Instance(name, type, sources, destinations, prepared));
            }
            validateCycles(instances);
            return new NetworkConfig(endpoints, instances);
        } catch (RuntimeException exception) {
            instances.forEach(i -> i.module()
                .close());
            throw exception;
        }
    }

    private static List<Endpoint> resolve(Value value, Map<String, Endpoint> endpoints,
        boolean source) {
        if (value == null) {
            return List.of();
        }
        List<Endpoint> resolved = new ArrayList<>();
        for (Value entry : value.list()) {
            Endpoint endpoint = endpoints.get(entry.string());
            if (endpoint == null) {
                throw entry.error("Unknown endpoint");
            }
            if (endpoint.kind() == (source ? Endpoint.Kind.OUTPUT : Endpoint.Kind.INPUT)) {
                throw entry.error("Wrong-direction endpoint");
            }
            if (resolved.contains(endpoint)) {
                throw entry.error("Duplicate route reference");
            }
            resolved.add(endpoint);
        }
        if (resolved.isEmpty()) {
            throw value.error("Nonempty endpoint list required");
        }
        return List.copyOf(resolved);
    }

    private static void validateCycles(List<Instance> instances) {
        Map<Endpoint, Set<Endpoint>> graph = new HashMap<>();
        for (Instance instance : instances) {
            for (Endpoint source : instance.sources()) {
                graph.computeIfAbsent(source, _ -> new HashSet<>())
                    .addAll(instance.destinations());
            }
        }
        Set<Endpoint> done = new HashSet<>();
        for (Endpoint node : graph.keySet()) {
            visit(node, graph, new HashSet<>(), done);
        }
    }

    private static void visit(Endpoint node, Map<Endpoint, Set<Endpoint>> graph,
        Set<Endpoint> visiting, Set<Endpoint> done) {
        if (done.contains(node)) {
            return;
        }
        if (!visiting.add(node)) {
            throw new IllegalArgumentException(
                "Internal routing cycle at %s".formatted(node.name()));
        }
        for (Endpoint next : graph.getOrDefault(node, Set.of())) {
            visit(next, graph, visiting, done);
        }
        visiting.remove(node);
        done.add(node);
    }
}
