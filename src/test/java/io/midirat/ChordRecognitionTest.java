package io.midirat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import io.midirat.chord.ChordSettings;
import io.midirat.chord.JavaChordRecognizer;
import io.midirat.config.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Independent musical fixtures from docs/chord-specification.md, not library snapshots. */
class ChordRecognitionTest {
    @TempDir
    Path directory;

    static Stream<Arguments> fixtures() {
        return Stream.of(
            Arguments.of("C", List.of(60, 64, 67)),
            Arguments.of("Cm", List.of(60, 63, 67)),
            Arguments.of("Cdim", List.of(60, 63, 66)),
            Arguments.of("Caug", List.of(60, 64, 68)),
            Arguments.of("Csus2", List.of(60, 62, 67)),
            Arguments.of("Csus4", List.of(60, 65, 67)),
            Arguments.of("C6", List.of(60, 64, 67, 69)),
            Arguments.of("Cm6", List.of(60, 63, 67, 69)),
            Arguments.of("C(b6)", List.of(60, 64, 67, 68)),
            Arguments.of("Cdim6", List.of(60, 63, 66, 69)),
            Arguments.of("Cdim(b6)", List.of(60, 63, 66, 68)),
            Arguments.of("Caug6", List.of(60, 64, 68, 69)),
            Arguments.of("Em(b6)", List.of(52, 55, 59, 60)),
            Arguments.of("C7", List.of(60, 64, 67, 70)),
            Arguments.of("Cmaj7", List.of(60, 64, 67, 71)),
            Arguments.of("Cm7", List.of(60, 63, 67, 70)),
            Arguments.of("Cm(maj7)", List.of(60, 63, 67, 71)),
            Arguments.of("Cdim7", List.of(60, 63, 66, 69)),
            Arguments.of("Cm7b5", List.of(60, 63, 66, 70)),
            Arguments.of("Cdim(maj7)", List.of(60, 63, 66, 71)),
            Arguments.of("Caug7", List.of(60, 64, 68, 70)),
            Arguments.of("Caug(maj7)", List.of(60, 64, 68, 71)),
            Arguments.of("C7sus4", List.of(60, 65, 67, 70)),
            Arguments.of("Cmaj7sus4", List.of(60, 65, 67, 71)),
            Arguments.of("C9", List.of(60, 64, 67, 70, 74)),
            Arguments.of("Cm9", List.of(60, 63, 67, 70, 74)),
            Arguments.of("Cmaj9", List.of(60, 64, 67, 71, 74)),
            Arguments.of("Cm(maj9)", List.of(60, 63, 67, 71, 74)),
            Arguments.of("Cdim9", List.of(60, 63, 66, 69, 74)),
            Arguments.of("Cm9b5", List.of(60, 63, 66, 70, 74)),
            Arguments.of("Caug(maj9)", List.of(60, 64, 68, 71, 74)),
            Arguments.of("C11", List.of(60, 64, 67, 70, 74, 77)),
            Arguments.of("Cm11", List.of(60, 63, 67, 70, 74, 77)),
            Arguments.of("C13", List.of(60, 64, 67, 70, 74, 77, 81)),
            Arguments.of("Cm13", List.of(60, 63, 67, 70, 74, 77, 81)),
            Arguments.of("C(add9)", List.of(60, 64, 67, 74)),
            Arguments.of("C(add11)", List.of(60, 64, 67, 77)),
            Arguments.of("C(add13)", List.of(60, 64, 67, 81)),
            Arguments.of("C(add9,11)", List.of(60, 64, 67, 74, 77)),
            Arguments.of("C(add9,13)", List.of(60, 64, 67, 74, 81)),
            Arguments.of("C(add11,13)", List.of(60, 64, 67, 77, 81)),
            Arguments.of("C(add9,11,13)", List.of(60, 64, 67, 74, 77, 81)),
            Arguments.of("Gm7(add11)", List.of(43, 50, 58, 60, 65)),
            Arguments.of("Am6/9", List.of(45, 48, 52, 54, 59)),
            Arguments.of("Am6/9(add11)", List.of(45, 48, 52, 54, 59, 62)),
            Arguments.of("C7(addb9)", List.of(60, 64, 67, 70, 73)),
            Arguments.of("C7(add#11)", List.of(60, 64, 67, 70, 78)));
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void recognizesMusicalFormulas(String label, List<Integer> pitches) {
        assertTrue(new JavaChordRecognizer().recognize(pitches)
            .contains(label), label);
    }

    @Test
    void transposesAndInvertsIndependentlyOfInputOrderOrDoublings() {
        var recognizer = new JavaChordRecognizer();
        var roots = List.of("C", "Db", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B");
        var intervals = List.of(0, 3, 7, 10, 14);
        for (int root = 0; root < 12; root++) {
            for (int inversion = 0; inversion < intervals.size(); inversion++) {
                int offset = root;
                int rotation = inversion;
                var pitches = IntStream.range(0, intervals.size())
                    .map(index -> 36 + offset + intervals.get((index + rotation) % intervals.size())
                        + (index + rotation >= intervals.size() ? 24 : 0))
                    .boxed()
                    .toList();
                int bass = pitches.stream()
                    .mapToInt(Integer::intValue)
                    .min()
                    .orElseThrow() % 12;
                var expected = "%sm9%s".formatted(roots.get(root), bass == root ? ""
                    : "/%s".formatted(roots.get(bass)));
                assertTrue(recognizer.recognize(pitches)
                    .contains(expected), expected);
            }
        }
        assertEquals(recognizer.recognize(List.of(60, 64, 67)),
            recognizer.recognize(List.of(79, 64, 60, 72, 67, 60)));
    }

    @Test
    void ranksRootReadingsAndEnharmonicsAndDeduplicates() {
        var recognizer = new JavaChordRecognizer();
        assertEquals("Gm7(add11)", recognizer.recognize(List.of(43, 50, 58, 60, 65))
            .getFirst());
        assertEquals("Am6/9", recognizer.recognize(List.of(45, 48, 52, 54, 59))
            .getFirst());
        assertEquals("Db", recognizer.recognize(List.of(61, 65, 68))
            .getFirst());
        assertEquals("C#m", recognizer.recognize(List.of(61, 64, 68))
            .getFirst());
        var labels = recognizer.recognize(List.of(60, 64, 67));
        assertEquals(labels.size(), labels.stream()
            .distinct()
            .count());
    }

    @Test
    void doesNotInventMissingTonesAndValidatesMidi() {
        var recognizer = new JavaChordRecognizer();
        assertEquals(List.of(), recognizer.recognize(List.of()));
        assertEquals(List.of(), recognizer.recognize(List.of(60)));
        assertEquals(List.of(), recognizer.recognize(List.of(60, 61, 62)));
        assertFalse(recognizer.recognize(List.of(60, 64, 70))
            .contains("C7"));
        assertFalse(recognizer.recognize(List.of(60, 64, 67, 70, 77))
            .contains("C11"));
        assertThrows(IllegalArgumentException.class, () -> recognizer.recognize(List.of(-1)));
        assertThrows(IllegalArgumentException.class, () -> recognizer.recognize(List.of(128)));
    }

    @Test
    void exposesVocabularySymbolsOmissionsSpellingAndSlashConfiguration() throws Exception {
        var strict = configured("upperStructures: false\nspelling: flats\nmaxAddedTones: 0");
        assertFalse(strict.recognize(List.of(53, 60, 64, 67))
            .contains("C/F"));
        assertFalse(strict.recognize(List.of(61, 65, 68))
            .contains("C#"));
        assertFalse(strict.recognize(List.of(60, 64, 67, 74))
            .contains("C(add9)"));
        assertTrue(configured("omittedFifth: true").recognize(List.of(60, 64, 70))
            .contains("C7(no5)"));
        assertTrue(configured("sixNine: false\nadditionStyle: repeated")
            .recognize(List.of(45, 48, 52, 54, 59))
            .contains("Am6add9"));
        assertTrue(configured("symbols: {minor: min, major: Δ, diminished: °, augmented: +}")
            .recognize(List.of(60, 63, 67))
            .contains("Cmin"));
        assertFalse(configured("disabledBases: [minor]").recognize(List.of(60, 63, 67))
            .contains("Cm"));
        var custom = configured(
            "bases: [{id: custom, suffix: quartal, degrees: ['1', '4', 'b7']}]");
        assertTrue(custom.recognize(List.of(60, 65, 70))
            .contains("Cquartal"));
        assertTrue(configured("ranking: [complexity, slash, order]")
            .recognize(List.of(60, 64, 67))
            .contains("C"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "unknown: true", "spelling: automatic", "maxAddedTones: 8",
            "upperStructures: yes", "ranking: [unknown]", "ranking: [slash, slash]",
            "additions: ['9', '2']", "additions: ['9', '9']", "disabledBases: [absent]",
            "bases: [{id: broken, suffix: bad, degrees: ['3', '5', '7']}]",
            "bases: [{id: broken, suffix: bad, degrees: ['1', '5', '12']}]",
            "symbols: {unknown: x}" })
    void rejectsInvalidConfiguration(String yaml) {
        assertThrows(IllegalArgumentException.class, () -> configured(yaml));
    }

    private JavaChordRecognizer configured(String yaml) throws Exception {
        Path file = directory.resolve("recognition.yaml");
        Files.writeString(file, yaml);
        return new JavaChordRecognizer(ChordSettings.read(Value.read(file)));
    }
}
