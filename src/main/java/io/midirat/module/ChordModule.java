package io.midirat.module;

import java.util.List;
import java.util.stream.Stream;

import io.midirat.api.ModuleDetails;
import io.midirat.api.ModuleInstance;
import io.midirat.api.ModuleWidget;
import io.midirat.chord.ChordRecognizer;
import io.midirat.chord.HeldNotes;
import io.midirat.midi.Envelope;
import io.midirat.midi.MidiMessage;
import io.midirat.presentation.ChordFormat;

/** Observer-local held keys, independent of endpoint identity; never emits MIDI. */
public final class ChordModule implements ModuleInstance, ModuleDetails {
    private final ChordRecognizer recognizer;
    private final int maxChords;
    private final List<String> recognitionInformation;
    private final HeldNotes held = new HeldNotes();
    private volatile List<String> lines = ChordFormat.lines(List.of(), List.of());
    private final ModuleWidget widget = new ModuleWidget() {
        @Override
        public List<String> lines(int availableLines) {
            return widgetLines();
        }

        @Override
        public int preferredWidth() {
            return 94;
        }
    };

    public ChordModule(ChordRecognizer recognizer) {
        this(recognizer, 0);
    }

    public ChordModule(ChordRecognizer recognizer, int maxChords) {
        this(recognizer, maxChords, List.of());
    }

    public ChordModule(ChordRecognizer recognizer, int maxChords,
        List<String> recognitionInformation) {
        if (maxChords < 0) {
            throw new IllegalArgumentException("maxChords must be nonnegative");
        }
        this.recognizer = recognizer;
        this.maxChords = maxChords;
        this.recognitionInformation = List.copyOf(recognitionInformation);
    }

    @Override
    public List<MidiMessage> process(Envelope envelope) {
        if (held.accept(envelope.message())) {
            List<Integer> pitches = held.pitches();
            List<String> candidates = recognizer.recognize(pitches)
                .stream()
                .limit(maxChords == 0 ? Long.MAX_VALUE : maxChords)
                .toList();
            lines = ChordFormat.lines(pitches, candidates);
        }
        return List.of();
    }

    @Override
    public List<String> widgetLines() {
        return lines;
    }

    @Override
    public ModuleWidget widget() {
        return widget;
    }

    @Override
    public ModuleDetails details() {
        return this;
    }

    @Override
    public ModuleWidget content() {
        return widget;
    }

    @Override
    public String informationTitle() {
        return "Configuration";
    }

    @Override
    public List<String> information() {
        return Stream.concat(Stream.of("maxChords: %d%s".formatted(maxChords,
            maxChords == 0 ? " (unlimited)" : "")), recognitionInformation.stream())
            .toList();
    }

    @Override
    public String mode() {
        return "Chords";
    }
}
