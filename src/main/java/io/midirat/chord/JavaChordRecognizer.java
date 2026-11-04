package io.midirat.chord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.midirat.chord.ChordSettings.Base;

/** Stateless, exact pitch-class recognition over an immutable YAML-defined vocabulary. */
public final class JavaChordRecognizer implements ChordRecognizer {
    private static final List<String> SHARPS = List.of("C", "C#", "D", "D#", "E", "F",
        "F#", "G", "G#", "A", "A#", "B");
    private static final List<String> FLATS = List.of("C", "Db", "D", "Eb", "E", "F",
        "Gb", "G", "Ab", "A", "Bb", "B");
    private final ChordSettings settings;
    private final Map<Integer, List<Formula>> vocabulary;

    private record Formula(String suffix, List<Degree> degrees, boolean minor, int order) {
    }

    private record Candidate(String label, String family, int alterations, int slash,
        int spelling, int symbols, int complexity, int order, int keyCost, int bassKeyCost) {
        int score(String criterion) {
            return switch (criterion) {
            case "alterations" -> alterations;
            case "slash" -> slash;
            case "spelling" -> spelling;
            case "symbols" -> symbols;
            case "complexity" -> complexity;
            case "order" -> order;
            default -> throw new IllegalArgumentException("Unknown ranking criterion");
            };
        }
    }

    public JavaChordRecognizer() {
        this(ChordSettings.defaults());
    }

    public JavaChordRecognizer(ChordSettings settings) {
        this.settings = settings;
        vocabulary = compile(settings);
    }

    @Override
    public List<String> recognize(List<Integer> pitches) {
        if (pitches.isEmpty()) {
            return List.of();
        }
        if (pitches.stream()
            .anyMatch(pitch -> pitch == null
                || pitch < 0
                || pitch > 127)) {
            throw new IllegalArgumentException("Chord pitches must be MIDI integers 0..127");
        }
        int bass = pitches.stream()
            .mapToInt(Integer::intValue)
            .min()
            .orElseThrow() % 12;
        int mask = pitches.stream()
            .mapToInt(pitch -> 1 << (pitch % 12))
            .reduce(0, (a, b) -> a | b);
        List<Candidate> candidates = new ArrayList<>();
        collect(mask, bass, candidates);
        int upper = mask & ~(1 << bass);
        if (settings.upperStructures()
            && Integer.bitCount(upper) >= 3) {
            collect(upper, bass, candidates);
        }
        Comparator<Candidate> comparator = (left, right) -> 0;
        for (String criterion : settings.ranking()) {
            comparator = comparator.thenComparingInt(candidate -> candidate.score(criterion));
        }
        candidates.sort(comparator);
        // Reorder only enharmonic spellings in each family's existing slots.
        Map<String, List<Candidate>> peers = candidates.stream()
            .collect(
                Collectors.groupingBy(Candidate::family));
        peers.values()
            .forEach(group -> group.sort(Comparator.comparingInt(Candidate::keyCost)
                .thenComparingInt(Candidate::bassKeyCost)));
        Map<String, Integer> positions = new HashMap<>();
        List<String> result = new ArrayList<>();
        for (Candidate candidate : candidates) {
            int position = positions.merge(candidate.family(), 1, Integer::sum) - 1;
            String label = peers.get(candidate.family())
                .get(position)
                .label();
            if (!result.contains(label)) {
                result.add(label);
            }
        }
        return List.copyOf(result);
    }

    private void collect(int mask, int bass, List<Candidate> candidates) {
        for (int root = 0; root < 12; root++) {
            int relative = ((mask >>> root) | (mask << (12 - root))) & 4095;
            for (Formula formula : vocabulary.getOrDefault(relative, List.of())) {
                for (String rootName : names(root)) {
                    for (String bassName : names(bass)) {
                        boolean slash = root != bass;
                        String suffix = formula.suffix();
                        String label = slash ? "%s%s/%s".formatted(rootName, suffix, bassName)
                            : "%s%s".formatted(rootName, suffix);
                        int spelling = formula.degrees()
                            .stream()
                            .mapToInt(degree -> degree.spellingCost(rootName))
                            .sum();
                        candidates.add(new Candidate(label,
                            "%d:%s:%d".formatted(root, suffix, bass),
                            accidentals(suffix), slash ? 1 : 0, spelling, accidentals(label),
                            suffix.length(), formula.order(), keyCost(rootName, formula.minor()),
                            keyCost(bassName, false)));
                        if (!slash) {
                            break;
                        }
                    }
                }
            }
        }
    }

    private List<String> names(int pitchClass) {
        String sharp = SHARPS.get(pitchClass);
        String flat = FLATS.get(pitchClass);
        return switch (settings.spelling()) {
        case "sharps" -> List.of(sharp);
        case "flats" -> List.of(flat);
        default -> sharp.equals(flat) ? List.of(sharp) : List.of(sharp, flat);
        };
    }

    private static int accidentals(String text) {
        return (int) text.chars()
            .filter(character -> character == '#'
                || character == 'b')
            .count();
    }

    private static int keyCost(String root, boolean minor) {
        int fifths = switch (root.charAt(0)) {
        case 'C' -> 0;
        case 'D' -> 2;
        case 'E' -> 4;
        case 'F' -> -1;
        case 'G' -> 1;
        case 'A' -> 3;
        case 'B' -> 5;
        default -> throw new IllegalArgumentException("Invalid root");
        };
        if (root.length() > 1) {
            fifths += root.charAt(1) == '#' ? 7 : -7;
        }
        return Math.abs(fifths - (minor ? 3 : 0));
    }

    private static Map<Integer, List<Formula>> compile(ChordSettings settings) {
        Map<Integer, List<Formula>> result = new LinkedHashMap<>();
        int order = 0;
        for (Base base : settings.bases()) {
            for (int subset = 0; subset < (1 << settings.additions()
                .size()); subset++) {
                if (Integer.bitCount(subset) > settings.maxAddedTones()) {
                    continue;
                }
                List<Degree> added = new ArrayList<>();
                List<Degree> degrees = new ArrayList<>(base.degrees());
                int mask = mask(degrees);
                boolean duplicate = false;
                for (int index = 0; index < settings.additions()
                    .size(); index++) {
                    if ((subset & (1 << index)) != 0) {
                        Degree degree = settings.additions()
                            .get(index);
                        duplicate |= (mask & (1 << degree.pitchClass())) != 0;
                        mask |= 1 << degree.pitchClass();
                        added.add(degree);
                        degrees.add(degree);
                    }
                }
                if (duplicate) {
                    continue;
                }
                added.sort(Comparator.comparingInt(Degree::number)
                    .thenComparingInt(Degree::alteration));
                String suffix = render(base, added, settings);
                Formula formula = new Formula(suffix, List.copyOf(degrees), base.minor(), order++);
                result.computeIfAbsent(mask, _ -> new ArrayList<>())
                    .add(formula);
                if (settings.omittedFifth()
                    && degrees.size() > 3
                    && degrees.stream()
                        .anyMatch(degree -> degree.label()
                            .equals("5"))) {
                    List<Degree> omitted = degrees.stream()
                        .filter(degree -> !degree.label()
                            .equals("5"))
                        .toList();
                    result.computeIfAbsent(mask(omitted), _ -> new ArrayList<>())
                        .add(new Formula("%s(no5)".formatted(suffix), omitted, base.minor(),
                            order++));
                }
            }
        }
        return result.entrySet()
            .stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                entry -> List.copyOf(entry.getValue())));
    }

    private static int mask(List<Degree> degrees) {
        return degrees.stream()
            .mapToInt(degree -> 1 << degree.pitchClass())
            .reduce(0, (left, right) -> left | right);
    }

    private static String render(Base base, List<Degree> added, ChordSettings settings) {
        String suffix = base.suffix();
        List<Degree> remaining = new ArrayList<>(added);
        if (base.extensionPrefix() != null) {
            int highest = 0;
            for (int number : List.of(9, 11, 13)) {
                if (!remaining.remove(Degree.parse(Integer.toString(number)))) {
                    break;
                }
                highest = number;
            }
            if (highest != 0) {
                suffix = "%s%d%s".formatted(base.extensionPrefix(), highest,
                    base.extensionSuffix());
            }
        } else if (settings.sixNine()
            && (base.id()
                .equals("major6")
                || base.id()
                    .equals("minor6"))
            && remaining.remove(Degree.parse("9"))) {
            suffix = "%s/9".formatted(suffix);
        }
        StringBuilder label = new StringBuilder(suffix);
        if (!remaining.isEmpty()) {
            if (settings.additionStyle()
                .equals("parenthesized")) {
                label.append("(add")
                    .append(remaining.stream()
                        .map(Degree::label)
                        .collect(Collectors.joining(",")))
                    .append(')');
            } else {
                remaining.forEach(degree -> label.append("add")
                    .append(degree.label()));
            }
        }
        String result = label.toString();
        for (Map.Entry<String, String> symbol : settings.symbols()
            .entrySet()) {
            result = result.replace("{%s}".formatted(symbol.getKey()), symbol.getValue());
        }
        return result;
    }
}
