package io.midirat.presentation;

import java.util.List;
import java.util.stream.Collectors;

/** Plain display text; recognition and key ownership remain in the chord domain. */
public final class ChordFormat {
    private ChordFormat() {
    }

    public static List<String> lines(List<Integer> pitches, List<String> candidates) {
        String chord = pitches.isEmpty() ? "--"
            : candidates.isEmpty() ? "unrecognized" : String.join(" | ", candidates);
        String notes = pitches.isEmpty() ? "--"
            : pitches.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
        String names = pitches.isEmpty() ? "--"
            : pitches.stream()
                .map(NoteNames::pitchClass)
                .collect(Collectors.joining(", "));
        return List.of("Chord: %s".formatted(chord), "Note names: %s".formatted(names),
            "Held MIDI notes: %s".formatted(notes));
    }
}
