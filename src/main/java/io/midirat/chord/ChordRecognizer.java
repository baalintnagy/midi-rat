package io.midirat.chord;

import java.util.List;

/** Recognize ascending MIDI pitches, preserving the lowest note as the bass. */
public interface ChordRecognizer {
    List<String> recognize(List<Integer> pitches);
}
