package io.midirat.presentation;

import java.util.List;

/** Shared pitch-class labels for monitor rows and chord held-note presentation. */
public final class NoteNames {
    private static final List<String> PITCH_CLASSES = List.of(
        "C", "(C#,Db)", "D", "(D#,Eb)", "E", "F", "(F#,Gb)", "G", "(G#,Ab)", "A", "(A#,Bb)", "B");

    private NoteNames() {
    }

    public static String pitchClass(int midiNote) {
        return PITCH_CLASSES.get(midiNote % 12);
    }
}
