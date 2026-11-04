package io.midirat.module;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import io.midirat.api.ModuleInstance;
import io.midirat.api.ModuleProvider;
import io.midirat.api.ModuleProvider.Role;
import io.midirat.chord.ChordSettings;
import io.midirat.chord.JavaChordRecognizer;
import io.midirat.config.Value;
import io.midirat.midi.Endpoint;
import io.midirat.presentation.YamlFormat;
import io.midirat.rule.MessageRule;
import io.midirat.rule.RuleDecoder;

public final class Builtins {
    private Builtins() {
    }

    public static List<ModuleProvider> providers() {
        return List.of(
            new Provider("midi-message-filter", Role.PROCESSOR, Builtins::filter),
            new Provider("midi-monitor", Role.MONITOR, Builtins::monitor),
            new Provider("midi-chord", Role.MONITOR, Builtins::chord),
            new Provider("midi-emitter", Role.EMITTER, Builtins::emitter),
            new Provider("midi-remap", Role.PROCESSOR, Builtins::remap));
    }

    private static ModuleInstance filter(Value settings, List<Endpoint> sources) {
        Map<String, Value> fields = settings.mapping("whitelist", "blacklist");
        return new FilterModule(rules(fields.get("whitelist"), sources),
            rules(fields.get("blacklist"), sources), YamlFormat.lines(settings));
    }

    private static ModuleInstance monitor(Value settings, List<Endpoint> sources) {
        Map<String, Value> fields = settings.mapping("historyLimit", "colors");
        return new MonitorModule(fields.containsKey("historyLimit")
            ? fields.get("historyLimit")
                .integer(1, 100_000)
            : 2000,
            !fields.containsKey("colors")
                || fields.get("colors")
                    .bool());
    }

    private static ModuleInstance chord(Value settings, List<Endpoint> sources) {
        Map<String, Value> fields = settings.mapping("maxChords", "recognition");
        return new ChordModule(new JavaChordRecognizer(fields.containsKey("recognition")
            ? ChordSettings.read(fields.get("recognition"))
            : ChordSettings.defaults()), fields.containsKey("maxChords")
                ? fields.get("maxChords")
                    .integer(0, Integer.MAX_VALUE)
                : 0,
            fields.containsKey("recognition") ? YamlFormat.lines(fields.get("recognition"))
                : List.of());
    }

    private static ModuleInstance emitter(Value settings, List<Endpoint> sources) {
        Map<String, Value> fields = settings.mapping("format", "messages");
        return new EmitterModule(fields.containsKey("format") ? fields.get("format")
            .string() : "hex",
            fields.containsKey("messages") ? fields.get("messages")
                .list()
                .stream()
                .map(Value::string)
                .toList() : List.of());
    }

    private static ModuleInstance remap(Value settings, List<Endpoint> sources) {
        Map<String, Value> fields = settings.mapping("curves", "rules");
        Map<String, Curve> curves = fields.containsKey("curves")
            ? ActionDecoder.curves(fields.get("curves"))
            : Map.of();
        List<RemapModule.Rule> rules = Value.required(fields, "rules", settings)
            .list()
            .stream()
            .map(rule -> remapRule(rule, sources, curves))
            .toList();
        return new RemapModule(rules);
    }

    private static RemapModule.Rule remapRule(Value rule, List<Endpoint> sources,
        Map<String, Curve> curves) {
        Map<String, Value> fields = rule.mapping("match", "action", "flow");
        MessageRule match = RuleDecoder.decode(Value.required(fields, "match", rule), sources);
        String flow = fields.containsKey("flow") ? fields.get("flow")
            .string() : "stop";
        if (!flow.equals("stop")
            && !flow.equals("continue")) {
            throw rule.error("flow must be stop or continue");
        }
        return new RemapModule.Rule(match,
            ActionDecoder.decode(Value.required(fields, "action", rule), match.type(), curves),
            flow.equals("stop"));
    }

    private static List<MessageRule> rules(Value value, List<Endpoint> sources) {
        return value == null ? List.of()
            : value.list()
                .stream()
                .map(v -> RuleDecoder.decode(v, sources))
                .toList();
    }

    private record Provider(String type, Role role,
        BiFunction<Value, List<Endpoint>, ModuleInstance> factory) implements ModuleProvider {
        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public int apiVersion() {
            return HOST_API_VERSION;
        }

        @Override
        public ModuleInstance prepare(Value settings, List<Endpoint> sources) {
            return factory.apply(settings, sources);
        }
    }
}
