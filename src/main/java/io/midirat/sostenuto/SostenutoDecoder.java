package io.midirat.sostenuto;

import java.util.List;
import java.util.Map;

import io.midirat.config.Value;
import io.midirat.midi.Endpoint;
import io.midirat.sostenuto.SostenutoSettings.Trigger;
import io.midirat.sostenuto.SostenutoSettings.UmbrellaTiming;

public final class SostenutoDecoder {
    private SostenutoDecoder() {
    }

    public static SostenutoSettings decode(Value settings, List<Endpoint> sources) {
        Map<String, Value> fields = settings.mapping("activationThreshold", "hysteresis",
            "trigger", "controlSources", "controlOrigins", "umbrella");
        if (fields.containsKey("controlSources")
            && fields.containsKey("controlOrigins")) {
            throw settings.error("Choose controlSources or controlOrigins, not both");
        }
        Trigger trigger = new Trigger(false, 22, false, false);
        if (fields.containsKey("trigger")) {
            Value value = fields.get("trigger");
            Map<String, Value> values = value.mapping("cc", "note", "toggle", "invert");
            if (values.containsKey("cc")
                && values.containsKey("note")) {
                throw value.error("Choose either cc or note for the trigger");
            }
            boolean note = values.containsKey("note");
            boolean invert = bool(values, "invert", false);
            if (note
                && invert) {
                throw value.error("invert applies only to CC triggers");
            }
            trigger = new Trigger(note, integer(values, note ? "note" : "cc", 22, 0, 127),
                bool(values, "toggle", false), invert);
        }
        List<Endpoint> controls = fields.containsKey("controlSources")
            ? fields.get("controlSources")
                .list()
                .stream()
                .map(value -> sources.stream()
                    .filter(source -> source.name()
                        .equals(value.string()))
                    .findFirst()
                    .orElseThrow(() -> value.error("Unknown or unsubscribed control source")))
                .toList()
            : List.of();
        if (fields.containsKey("controlSources")
            && (controls.isEmpty()
                || controls.stream()
                    .distinct()
                    .count() != controls.size())) {
            throw fields.get("controlSources")
                .error("Nonempty, unique control sources required");
        }
        List<String> origins = fields.containsKey("controlOrigins")
            ? fields.get("controlOrigins")
                .list()
                .stream()
                .map(Value::string)
                .toList()
            : List.of();
        if (fields.containsKey("controlOrigins")
            && (origins.isEmpty()
                || origins.stream()
                    .distinct()
                    .count() != origins.size())) {
            throw fields.get("controlOrigins")
                .error("Nonempty, unique control origins required");
        }
        UmbrellaTiming umbrella = new UmbrellaTiming(false, 1, 1);
        if (fields.containsKey("umbrella")) {
            Map<String, Value> values = fields.get("umbrella")
                .mapping("enabled", "delayMs", "tailMs");
            umbrella = new UmbrellaTiming(bool(values, "enabled", false),
                integer(values, "delayMs", 1, 0, 60_000),
                integer(values, "tailMs", 1, 0, 60_000));
        }
        return new SostenutoSettings(integer(fields, "activationThreshold", 10, 1, 127),
            integer(fields, "hysteresis", 2, 0, 127), trigger, controls, origins, umbrella);
    }

    private static int integer(Map<String, Value> fields, String name, int fallback,
        int min, int max) {
        return fields.containsKey(name) ? fields.get(name)
            .integer(min, max) : fallback;
    }

    private static boolean bool(Map<String, Value> fields, String name, boolean fallback) {
        return fields.containsKey(name) ? fields.get(name)
            .bool() : fallback;
    }
}
