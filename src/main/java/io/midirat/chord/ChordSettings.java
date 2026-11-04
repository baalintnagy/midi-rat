package io.midirat.chord;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.midirat.config.Value;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;

/** Immutable validated configuration. All musical vocabulary comes from YAML. */
public record ChordSettings(List<Base> bases, List<Degree> additions, int maxAddedTones,
    String spelling, boolean upperStructures, boolean omittedFifth, boolean sixNine,
    String additionStyle, Map<String, String> symbols, List<String> ranking) {
    private static final List<String> CRITERIA = List.of("alterations", "slash", "spelling",
        "symbols", "complexity", "order");

    public record Base(String id, String suffix, List<Degree> degrees, boolean minor,
        String extensionPrefix, String extensionSuffix) {
        public Base {
            degrees = List.copyOf(degrees);
        }
    }

    public ChordSettings {
        bases = List.copyOf(bases);
        additions = List.copyOf(additions);
        symbols = Map.copyOf(symbols);
        ranking = List.copyOf(ranking);
    }

    public static ChordSettings defaults() {
        try (InputStream stream = ChordSettings.class.getResourceAsStream("/chord-defaults.yaml")) {
            if (stream == null) {
                throw new IllegalStateException("Missing chord-defaults.yaml");
            }
            return decode(new Value(new Yaml().compose(
                new InputStreamReader(stream, StandardCharsets.UTF_8)), "chord defaults"), null);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read chord defaults", exception);
        }
    }

    public static ChordSettings read(Value value) {
        return decode(value, defaults());
    }

    private static ChordSettings decode(Value value, ChordSettings fallback) {
        Map<String, Value> fields = value.mapping("bases", "disabledBases", "additions",
            "maxAddedTones", "spelling", "upperStructures", "omittedFifth", "sixNine",
            "additionStyle", "symbols", "ranking");
        List<Base> bases = new ArrayList<>(fallback == null ? List.of() : fallback.bases());
        if (fields.containsKey("bases")) {
            Set<String> ids = new HashSet<>();
            for (Value item : fields.get("bases")
                .list()) {
                Base base = base(item);
                if (!ids.add(base.id())) {
                    throw item.error("Duplicate base id");
                }
                int position = -1;
                for (int index = 0; index < bases.size(); index++) {
                    if (bases.get(index)
                        .id()
                        .equals(base.id())) {
                        position = index;
                        break;
                    }
                }
                if (position < 0) {
                    bases.add(base);
                } else {
                    bases.set(position, base);
                }
            }
        }
        if (fields.containsKey("disabledBases")) {
            for (Value item : fields.get("disabledBases")
                .list()) {
                String id = item.string();
                if (!bases.removeIf(base -> base.id()
                    .equals(id))) {
                    throw item.error("Unknown or repeated disabled base");
                }
            }
        }
        List<Degree> additions = fields.containsKey("additions")
            ? degrees(fields.get("additions"))
            : fallback.additions();
        if (additions.size() > 7
            || additions.stream()
                .anyMatch(degree -> degree.number() < 9)) {
            throw value.error("At most seven added degrees, each 9, 11, or 13 with accidentals");
        }
        Map<String, String> symbols = new LinkedHashMap<>(fallback == null
            ? Map.of()
            : fallback.symbols());
        if (fields.containsKey("symbols")) {
            fields.get("symbols")
                .mapping("minor", "major", "diminished", "augmented", "halfDiminished")
                .forEach((key, item) -> symbols.put(key, text(item)));
        }
        List<String> ranking = fields.containsKey("ranking") ? fields.get("ranking")
            .list()
            .stream()
            .map(Value::string)
            .toList() : fallback.ranking();
        if (ranking.isEmpty()
            || !CRITERIA.containsAll(ranking)
            || new HashSet<>(ranking).size() != ranking.size()) {
            throw value.error("Ranking must contain unique supported criteria");
        }
        return new ChordSettings(bases, additions,
            integer(fields, "maxAddedTones", fallback == null ? 3 : fallback.maxAddedTones()),
            choice(fields, "spelling", fallback == null ? "both" : fallback.spelling(),
                List.of("both", "sharps", "flats")),
            bool(fields, "upperStructures", fallback == null
                || fallback.upperStructures()),
            bool(fields, "omittedFifth", fallback != null
                && fallback.omittedFifth()),
            bool(fields, "sixNine", fallback == null
                || fallback.sixNine()),
            choice(fields, "additionStyle",
                fallback == null ? "parenthesized" : fallback.additionStyle(),
                List.of("parenthesized", "repeated")),
            symbols, ranking);
    }

    private static Base base(Value value) {
        Map<String, Value> fields = value.mapping("id", "suffix", "degrees", "minor",
            "extensionPrefix", "extensionSuffix");
        List<Degree> degrees = degrees(Value.required(fields, "degrees", value));
        if (degrees.size() < 3
            || degrees.size() > 12
            || degrees.stream()
                .noneMatch(degree -> degree.label()
                    .equals("1"))) {
            throw value.error("Base needs root 1 and three to twelve distinct pitch classes");
        }
        return new Base(Value.required(fields, "id", value)
            .string(),
            text(Value.required(fields, "suffix", value)), degrees,
            bool(fields, "minor", false), fields.containsKey("extensionPrefix")
                ? text(fields.get("extensionPrefix"))
                : null,
            fields.containsKey("extensionSuffix") ? text(fields.get("extensionSuffix")) : "");
    }

    private static List<Degree> degrees(Value value) {
        List<Degree> result = new ArrayList<>();
        Set<Integer> classes = new HashSet<>();
        for (Value item : value.list()) {
            Degree degree;
            try {
                degree = Degree.parse(item.string());
            } catch (IllegalArgumentException exception) {
                throw item.error(exception.getMessage());
            }
            if (!classes.add(degree.pitchClass())) {
                throw item.error("Duplicate pitch class in degree list");
            }
            result.add(degree);
        }
        return List.copyOf(result);
    }

    private static String text(Value value) {
        if (!(value.node() instanceof ScalarNode scalar)
            || !value.node()
                .getTag()
                .equals(Tag.STR)
            || scalar.getValue()
                .contains("/")
            || scalar.getValue()
                .contains("\n")) {
            throw value.error("Expected single-line symbol without slash");
        }
        return scalar.getValue();
    }

    private static int integer(Map<String, Value> fields, String key, int fallback) {
        return fields.containsKey(key) ? fields.get(key)
            .integer(0, 7) : fallback;
    }

    private static boolean bool(Map<String, Value> fields, String key, boolean fallback) {
        return fields.containsKey(key) ? fields.get(key)
            .bool() : fallback;
    }

    private static String choice(Map<String, Value> fields, String key, String fallback,
        List<String> allowed) {
        String result = fields.containsKey(key) ? fields.get(key)
            .string() : fallback;
        if (!allowed.contains(result)) {
            throw fields.get(key)
                .error("Unsupported choice");
        }
        return result;
    }
}
