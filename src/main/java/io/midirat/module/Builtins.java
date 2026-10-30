package io.midirat.module;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import io.midirat.api.ModuleInstance;
import io.midirat.api.ModuleProvider;
import io.midirat.config.Value;
import io.midirat.midi.Endpoint;
import io.midirat.rule.MessageRule;
import io.midirat.rule.RuleDecoder;

public final class Builtins {
    private Builtins() {
    }

    public static List<ModuleProvider> providers() {
        return List
            .of(provider("midi-message-filter", ModuleProvider.Role.PROCESSOR, (v, sources) -> {
                Map<String, Value> f = v.mapping("whitelist", "blacklist");
                return new FilterModule(rules(f.get("whitelist"), sources),
                    rules(f.get("blacklist"), sources));
            }), provider("midi-monitor", ModuleProvider.Role.MONITOR, (v, sources) -> {
                Map<String, Value> f = v.mapping("historyLimit", "colors");
                return new MonitorModule(f.containsKey("historyLimit")
                    ? f.get("historyLimit")
                        .integer(1, 100_000)
                    : 2000,
                    !f.containsKey("colors")
                        || f.get("colors")
                            .bool());
            }), provider("midi-emitter", ModuleProvider.Role.EMITTER, (v, sources) -> {
                Map<String, Value> f = v.mapping("format", "messages");
                return new EmitterModule(f.containsKey("format") ? f.get("format")
                    .string() : "hex",
                    f.containsKey("messages") ? f.get("messages")
                        .list()
                        .stream()
                        .map(Value::string)
                        .toList() : List.of());
            }), provider("midi-remap", ModuleProvider.Role.PROCESSOR, (v, sources) -> {
                Map<String, Value> f = v.mapping("curves", "rules");
                Map<String, Curve> curves = f.containsKey("curves")
                    ? ActionDecoder.curves(f.get("curves"))
                    : Map.of();
                List<RemapModule.Rule> rules = Value.required(f, "rules", v)
                    .list()
                    .stream()
                    .map(r -> {
                        Map<String, Value> fields = r.mapping("match", "action", "flow");
                        MessageRule match = RuleDecoder.decode(Value.required(fields, "match", r),
                            sources);
                        String flow = fields.containsKey("flow") ? fields.get("flow")
                            .string() : "stop";
                        if (!flow.equals("stop")
                            && !flow.equals("continue")) {
                            throw r.error("flow must be stop or continue");
                        }
                        return new RemapModule.Rule(match, ActionDecoder.decode(
                            Value.required(fields, "action", r), match.type(), curves),
                            flow.equals("stop"));
                    })
                    .toList();
                return new RemapModule(rules);
            }));
    }

    private static List<MessageRule> rules(Value value, List<Endpoint> sources) {
        return value == null ? List.of()
            : value.list()
                .stream()
                .map(v -> RuleDecoder.decode(v, sources))
                .toList();
    }

    private static ModuleProvider provider(String type, ModuleProvider.Role role,
        BiFunction<Value, List<Endpoint>, ModuleInstance> prepare) {
        return new ModuleProvider() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public String version() {
                return "1.0.0";
            }

            @Override
            public int apiVersion() {
                return HOST_API_VERSION;
            }

            @Override
            public Role role() {
                return role;
            }

            @Override
            public ModuleInstance prepare(Value settings, List<Endpoint> sources) {
                return prepare.apply(settings, sources);
            }
        };
    }
}
