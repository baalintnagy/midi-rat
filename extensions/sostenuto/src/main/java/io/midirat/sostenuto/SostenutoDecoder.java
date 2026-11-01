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
        Trigger trigger = trigger(fields.get("trigger"));
        List<Endpoint> controls = controlSources(fields.get("controlSources"), sources);
        List<String> origins = controlOrigins(fields.get("controlOrigins"));
        UmbrellaTiming umbrella = umbrella(fields.get("umbrella"));
        return new SostenutoSettings(integer(fields, "activationThreshold", 10, 1, 127),
            integer(fields, "hysteresis", 2, 0, 127), trigger, controls, origins, umbrella);
    }

    private static Trigger trigger(Value value) {
        if (value == null) {
            return new Trigger(false, 22, false, false);
        }
        Map<String, Value> fields = value.mapping("cc", "note", "toggle", "invert");
        if (fields.containsKey("cc")
            && fields.containsKey("note")) {
            throw value.error("Choose either cc or note for the trigger");
        }
        boolean note = fields.containsKey("note");
        boolean invert = bool(fields, "invert", false);
        if (note
            && invert) {
            throw value.error("invert applies only to CC triggers");
        }
        return new Trigger(note, integer(fields, note ? "note" : "cc", 22, 0, 127),
            bool(fields, "toggle", false), invert);
    }

    private static List<Endpoint> controlSources(Value value, List<Endpoint> sources) {
        if (value == null) {
            return List.of();
        }
        List<Endpoint> selected = value.list()
            .stream()
            .map(entry -> sources.stream()
                .filter(source -> source.name()
                    .equals(entry.string()))
                .findFirst()
                .orElseThrow(() -> entry.error("Unknown or unsubscribed control source")))
            .toList();
        if (selected.isEmpty()
            || selected.stream()
                .distinct()
                .count() != selected.size()) {
            throw value.error("Nonempty, unique control sources required");
        }
        return selected;
    }

    private static List<String> controlOrigins(Value value) {
        if (value == null) {
            return List.of();
        }
        List<String> selected = value.list()
            .stream()
            .map(Value::string)
            .toList();
        if (selected.isEmpty()
            || selected.stream()
                .distinct()
                .count() != selected.size()) {
            throw value.error("Nonempty, unique control origins required");
        }
        return selected;
    }

    private static UmbrellaTiming umbrella(Value value) {
        if (value == null) {
            return new UmbrellaTiming(false, 1, 1);
        }
        Map<String, Value> fields = value.mapping("enabled", "delayMs", "tailMs");
        return new UmbrellaTiming(bool(fields, "enabled", false),
            integer(fields, "delayMs", 1, 0, 60_000),
            integer(fields, "tailMs", 1, 0, 60_000));
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
