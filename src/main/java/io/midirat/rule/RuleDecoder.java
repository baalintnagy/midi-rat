package io.midirat.rule;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import io.midirat.config.Value;
import io.midirat.midi.Endpoint;
import io.midirat.midi.MessageType;
import io.midirat.rule.MessageRule.Channel;
import io.midirat.rule.MessageRule.Scope;

public final class RuleDecoder {
    private RuleDecoder() {
    }

    public static MessageRule decode(Value value, List<Endpoint> subscriptions) {
        Map<String, Value> fields = value.mapping();
        MessageType type;
        try {
            type = MessageType.valueOf(Value.required(fields, "type", value)
                .string());
        } catch (IllegalArgumentException exception) {
            throw value.error("Unknown scalar message type");
        }
        Set<String> allowed = new HashSet<>(Set.of("type", "sources", "statuses"));
        if (type.channel()) {
            allowed.add("channels");
        }
        if (type.length() >= 2) {
            allowed.add("data1");
        }
        if (type.length() >= 3) {
            allowed.add("data2");
        }
        switch (type) {
        case NOTE, NOTE_ON, NOTE_OFF -> allowed.addAll(Set.of("notes", "velocity"));
        case CC -> allowed.addAll(Set.of("ccs", "value"));
        case P_BEND -> allowed.add("value");
        case CH_PR -> allowed.add("pressure");
        case POLY_AT -> allowed.addAll(Set.of("notes", "pressure"));
        case PC -> allowed.add("programs");
        case SONG_POSITION -> allowed.add("position");
        case SONG_SELECT -> allowed.add("songs");
        default -> {
        }
        }
        fields.keySet()
            .forEach(key -> {
                if (!allowed.contains(key)) {
                    throw fields.get(key)
                        .error("Inapplicable predicate");
                }
            });
        Set<Endpoint> sources = new HashSet<>();
        if (fields.containsKey("sources")) {
            if (fields.get("sources")
                .list()
                .isEmpty()) {
                throw value.error("Empty sources");
            }
            for (Value source : fields.get("sources")
                .list()) {
                Endpoint endpoint = subscriptions.stream()
                    .filter(e -> e.name()
                        .equals(source.string()))
                    .findFirst()
                    .orElseThrow(() -> source.error("Source is not subscribed"));
                if (!sources.add(endpoint)) {
                    throw source.error("Duplicate source");
                }
            }
        }
        IntSelection statuses = select(fields, "statuses", 128, 255, true);
        IntSelection channels = select(fields, "channels", 1, 16, true);
        Scope scope = new Scope(sources, statuses);
        Channel channel = new Channel(channels, numeric(fields, "data1", 127),
            numeric(fields, "data2", 127));
        if (IntStream.rangeClosed(128, 255)
            .noneMatch(status -> matchesScope(type, status, statuses, channels))) {
            throw value.error("Contradictory type/channel/status predicates");
        }
        MessageRule compiled = switch (type) {
        case NOTE, NOTE_ON, NOTE_OFF -> new MessageRule.Note(type, scope, channel,
            select(fields, "notes", 0, 127, false), numeric(fields, "velocity", 127));
        case CC -> new MessageRule.Control(scope, channel,
            select(fields, "ccs", 0, 127, false), numeric(fields, "value", 127));
        case P_BEND -> new MessageRule.Bend(scope, channel, numeric(fields, "value", 16383));
        case CH_PR -> new MessageRule.Pressure(scope, channel, numeric(fields, "pressure", 127));
        case POLY_AT -> new MessageRule.PolyPressure(scope, channel,
            select(fields, "notes", 0, 127, false), numeric(fields, "pressure", 127));
        case PC -> new MessageRule.Program(scope, channel,
            select(fields, "programs", 0, 127, true));
        case TIME_CODE, SONG_POSITION, SONG_SELECT -> new MessageRule.SystemData(type, scope,
            numeric(fields, "data1", 127), numeric(fields, "data2", 127),
            type == MessageType.SONG_POSITION ? numeric(fields, "position", 16383)
                : type == MessageType.SONG_SELECT ? select(fields, "songs", 0, 127, true)
                    : new IntSelection.Range(0, 127));
        default -> new MessageRule.SystemSignal(type, scope);
        };
        boolean feasible = IntStream.rangeClosed(128, 255)
            .anyMatch(status -> matchesScope(type, status, statuses, channels)
                && feasiblePayload(compiled, status));
        if (!feasible) {
            throw value.error("Contradictory raw/semantic predicates");
        }
        return compiled;
    }

    private static boolean matchesScope(MessageType type, int status, IntSelection statuses,
        IntSelection channels) {
        MessageType actual = MessageType.of(status);
        boolean kind = actual == type
            || type == MessageType.NOTE
                && (actual == MessageType.NOTE_ON
                    || actual == MessageType.NOTE_OFF)
            || type == MessageType.NOTE_OFF
                && actual == MessageType.NOTE_ON
            || type == MessageType.SYSEX_START
                && status == 0xF0;
        return kind
            && statuses.contains(status)
            && (!type.channel()
                || channels.contains((status & 15) + 1));
    }

    private static boolean intersects(IntSelection a, IntSelection b, int min, int max) {
        return IntStream.rangeClosed(min, max)
            .anyMatch(v -> a.contains(v)
                && b.contains(v));
    }

    private static boolean wide(IntSelection semantic, IntSelection low, IntSelection high) {
        return IntStream.rangeClosed(0, 16383)
            .anyMatch(v -> semantic.contains(v)
                && low.contains(v & 127)
                && high.contains(v >> 7));
    }

    private static boolean feasiblePayload(MessageRule rule, int status) {
        return switch (rule) {
        case MessageRule.Note n -> intersects(n.notes(), n.channel()
            .raw1(), 0, 127)
            && intersects(n.velocity(), n.channel()
                .raw2(),
                n.type() == MessageType.NOTE_ON ? 1 : 0,
                n.type() == MessageType.NOTE_OFF
                    && (status & 0xF0) == 0x90 ? 0 : 127);
        case MessageRule.Control c -> intersects(c.controllers(), c.channel()
            .raw1(), 0, 127)
            && intersects(c.value(), c.channel()
                .raw2(), 0, 127);
        case MessageRule.Bend b -> wide(b.value(), b.channel()
            .raw1(),
            b.channel()
                .raw2());
        case MessageRule.Pressure p -> intersects(p.pressure(), p.channel()
            .raw1(), 0, 127);
        case MessageRule.PolyPressure p -> intersects(p.notes(), p.channel()
            .raw1(), 0, 127)
            && intersects(p.pressure(), p.channel()
                .raw2(), 0, 127);
        case MessageRule.Program p -> intersects(p.programs(), p.channel()
            .raw1(), 0, 127);
        case MessageRule.SystemData s -> s.type() == MessageType.SONG_POSITION
            ? wide(s.semantic(), s.raw1(), s.raw2())
            : intersects(s.semantic(), s.raw1(), 0, 127);
        case MessageRule.SystemSignal _ -> true;
        };
    }

    private static IntSelection numeric(Map<String, Value> fields, String key, int max) {
        return fields.containsKey(key) ? IntSelection.range(fields.get(key), 0, max)
            : new IntSelection.Range(0, max);
    }

    private static IntSelection select(Map<String, Value> fields, String key,
        int min, int max, boolean listOnly) {
        return fields.containsKey(key) ? IntSelection.decode(fields.get(key), min, max, listOnly)
            : new IntSelection.Range(min, max);
    }
}
