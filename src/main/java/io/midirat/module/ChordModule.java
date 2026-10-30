package io.midirat.module;

import java.util.List;

import io.midirat.api.ModuleInstance;
import io.midirat.chord.ChordRecognizer;
import io.midirat.chord.HeldNotes;
import io.midirat.midi.Envelope;
import io.midirat.midi.MidiMessage;
import io.midirat.presentation.ChordFormat;

/** Observer-local held keys, independent of endpoint identity; never emits MIDI. */
public final class ChordModule implements ModuleInstance {
    private final ChordRecognizer recognizer;
    private final HeldNotes held = new HeldNotes();
    private volatile List<String> lines = ChordFormat.lines(List.of(), List.of());

    public ChordModule(ChordRecognizer recognizer) {
        this.recognizer = recognizer;
    }

    @Override
    public List<MidiMessage> process(Envelope envelope) {
        if (held.accept(envelope.message())) {
            List<Integer> pitches = held.pitches();
            lines = ChordFormat.lines(pitches, recognizer.recognize(pitches));
        }
        return List.of();
    }

    @Override
    public List<String> widgetLines() {
        return lines;
    }
}
