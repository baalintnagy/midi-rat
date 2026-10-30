package io.midirat.module;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import io.midirat.config.Value;
import io.midirat.midi.MessageCodec;
import io.midirat.midi.MessageType;

public final class ActionDecoder {
    private ActionDecoder() {
    }

    public static Map<String, Curve> curves(Value value) {
        Map<String, Curve> curves = new LinkedHashMap<>();
        value.mapping()
            .forEach((name, definition) -> {
                Map<String, Value> fields = definition.mapping("points");
                List<Curve.Point> points = Value.required(fields, "points", definition)
                    .list()
                    .stream()
                    .map(point -> {
                        Map<String, Value> p = point.mapping("input", "output");
                        return new Curve.Point(Value.required(p, "input", point)
                            .integer(0, 16383),
                            Value.required(p, "output", point)
                                .integer(0, 16383));
                    })
                    .toList();
                curves.put(name, new Curve(points));
            });
        return Map.copyOf(curves);
    }

    public static Action decode(Value value, MessageType type, Map<String, Curve> curves) {
        Map<String, Value> initial = value.mapping();
        String op = Value.required(initial, "op", value)
            .string();
        String[] keys = switch (op) {
        case "channel-map" -> new String[] { "op", "channels" };
        case "note-map" ->
            new String[] { "op", "entries", "fallbackTranspose", "bendRangeSemitones" };
        case "value-curve" -> new String[] { "op", "field", "curve" };
        case "cc-map", "pitch-bend-to-cc" -> new String[] { "op", "controllers", "curve" };
        case "cc-to-pitch-bend" -> new String[] { "op", "curve" };
        case "substitute" -> new String[] { "op", "format", "messages" };
        case "cc-note-trigger" -> new String[] { "op", "onThreshold", "offThreshold", "note",
                "velocity", "offVelocity", "channels", "passOriginal" };
        default -> throw value.error("Unknown action operation");
        };
        Map<String, Value> f = value.mapping(keys);
        boolean notes = Set.of(MessageType.NOTE, MessageType.NOTE_ON, MessageType.NOTE_OFF)
            .contains(type);
        boolean compatible = switch (op) {
        case "channel-map" -> type.channel();
        case "note-map" -> notes;
        case "cc-map", "cc-to-pitch-bend", "cc-note-trigger" -> type == MessageType.CC;
        case "pitch-bend-to-cc" -> type == MessageType.P_BEND;
        default -> true;
        };
        if (!compatible) {
            throw value.error("Action incompatible with match type");
        }
        return switch (op) {
        case "channel-map" -> new Action.ChannelMap(integers(f, "channels", value, 1, 16));
        case "note-map" -> noteMap(value, f);
        case "value-curve" -> {
            String field = Value.required(f, "field", value)
                .string();
            boolean valid = switch (field) {
            case "velocity" -> notes;
            case "value" -> type == MessageType.CC
                || type == MessageType.P_BEND;
            case "pressure" -> type == MessageType.CH_PR
                || type == MessageType.POLY_AT;
            case "position" -> type == MessageType.SONG_POSITION;
            default -> false;
            };
            if (!valid) {
                throw value.error("Inapplicable value-curve field");
            }
            int max = type == MessageType.P_BEND
                || type == MessageType.SONG_POSITION
                    ? 16383
                    : 127;
            yield new Action.ValueCurve(
                Action.Field.valueOf(field.toUpperCase(Locale.ROOT)),
                curve(f, value, curves, max));
        }
        case "cc-map" -> new Action.ControllerMap(integers(f, "controllers", value, 0, 127),
            f.containsKey("curve") ? curve(f, value, curves, 127)
                : new Curve(List.of(new Curve.Point(0, 0), new Curve.Point(127, 127))));
        case "cc-to-pitch-bend" -> new Action.CcToBend(curve(f, value, curves, 16383));
        case "pitch-bend-to-cc" -> new Action.BendToCc(
            integers(f, "controllers", value, 0, 127), curve(f, value, curves, 127));
        case "substitute" -> new Action.Substitute(MessageCodec.sequence(
            Value.required(f, "format", value)
                .string(),
            Value.required(f, "messages", value)
                .list()
                .stream()
                .map(Value::string)
                .toList()));
        case "cc-note-trigger" -> {
            int on = number(f, "onThreshold", value, 1, 127);
            int off = number(f, "offThreshold", value, 0, 126);
            if (off >= on) {
                throw value.error("offThreshold must be below onThreshold");
            }
            yield new Action.CcTrigger(on, off, number(f, "note", value, 0, 127),
                number(f, "velocity", value, 1, 127), number(f, "offVelocity", value, 0, 127),
                integers(f, "channels", value, 1, 16),
                Value.required(f, "passOriginal", value)
                    .bool());
        }
        default -> throw value.error("Unknown operation");
        };
    }

    private static Action.NoteMap noteMap(Value value, Map<String, Value> f) {
        Map<Integer, Action.NoteTarget> entries = new LinkedHashMap<>();
        if (f.containsKey("entries")) {
            for (Value entry : f.get("entries")
                .list()) {
                Map<String, Value> e = entry.mapping("from", "to");
                int from = number(e, "from", entry, 0, 127);
                Value target = Value.required(e, "to", entry);
                Map<String, Value> to = target.mapping("note", "pitchBend");
                OptionalInt bend = to.containsKey("pitchBend")
                    ? OptionalInt.of(to.get("pitchBend")
                        .integer(0, 16383))
                    : OptionalInt.empty();
                if (bend.isPresent()
                    && !f.containsKey("bendRangeSemitones")) {
                    throw entry.error("Bend lookup requires bendRangeSemitones");
                }
                if (entries.putIfAbsent(from,
                    new Action.NoteTarget(number(to, "note", target, 0, 127), bend)) != null) {
                    throw entry.error("Duplicate note lookup");
                }
            }
        }
        if (f.containsKey("bendRangeSemitones")) {
            f.get("bendRangeSemitones")
                .integer(1, Integer.MAX_VALUE);
        }
        int transpose = f.containsKey("fallbackTranspose")
            ? f.get("fallbackTranspose")
                .integer(Integer.MIN_VALUE, Integer.MAX_VALUE)
            : 0;
        return new Action.NoteMap(entries, transpose);
    }

    private static int number(Map<String, Value> f, String key, Value parent, int min, int max) {
        return Value.required(f, key, parent)
            .integer(min, max);
    }

    private static List<Integer> integers(Map<String, Value> f, String key, Value parent,
        int min, int max) {
        Value value = Value.required(f, key, parent);
        List<Integer> values = value.list()
            .stream()
            .map(v -> v.integer(min, max))
            .toList();
        if (values.isEmpty()) {
            throw value.error("Nonempty output list required");
        }
        return values;
    }

    private static Curve curve(Map<String, Value> f, Value parent,
        Map<String, Curve> curves, int max) {
        Value value = Value.required(f, "curve", parent);
        Curve curve = curves.get(value.string());
        if (curve == null) {
            throw value.error("Undefined curve");
        }
        curve.validateOutput(max);
        return curve;
    }
}
