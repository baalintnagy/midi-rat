package io.midirat.module;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import io.midirat.api.ModuleInstance;
import io.midirat.chord.ChordRecognizer;
import io.midirat.midi.Envelope;
import io.midirat.midi.MessageType;
import io.midirat.midi.MidiMessage;

/** Observer-local held keys, independent of endpoint identity; never emits MIDI. */
public final class ChordModule implements ModuleInstance {
    private final ChordRecognizer recognizer;
    private final int[][] held = new int[16][128];
    private volatile List<String> lines = List.of("Chord: --", "Held MIDI notes: --");

    public ChordModule(ChordRecognizer recognizer) {
        this.recognizer = recognizer;
    }

    @Override
    public List<MidiMessage> process(Envelope envelope) {
        MidiMessage message = envelope.message();
        MessageType type = message.matchType();
        if (type == MessageType.NOTE_ON) {
            held[message.channel() - 1][message.data1()]++;
        } else if (type == MessageType.NOTE_OFF) {
            int[] channel = held[message.channel() - 1];
            if (channel[message.data1()] == 0) {
                return List.of();
            }
            channel[message.data1()]--;
        } else if (type == MessageType.CC
            && (message.data1() == 120
                || message.data1() == 123)) {
            Arrays.fill(held[message.channel() - 1], 0);
        } else {
            return List.of();
        }
        List<Integer> pitches = IntStream.range(0, 128)
            .filter(pitch -> IntStream.range(0, 16)
                .anyMatch(channel -> held[channel][pitch] > 0))
            .boxed()
            .toList();
        List<String> chords = recognizer.recognize(pitches);
        String chord = pitches.isEmpty() ? "--"
            : chords.isEmpty() ? "unrecognized" : String.join(" | ", chords);
        String notes = pitches.isEmpty() ? "--"
            : pitches.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
        lines = List.of("Chord: %s".formatted(chord), "Held MIDI notes: %s".formatted(notes));
        return List.of();
    }

    @Override
    public List<String> widgetLines() {
        return lines;
    }
}
