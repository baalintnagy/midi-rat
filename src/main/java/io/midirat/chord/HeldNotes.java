package io.midirat.chord;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import io.midirat.midi.MessageType;
import io.midirat.midi.MidiMessage;

/** Observer-local key state: retriggers are idempotent; endpoint identity is irrelevant. */
public final class HeldNotes {
    private final boolean[][] channels = new boolean[16][128];

    /** Whether the input requires refreshing recognition, including retriggers and resets. */
    public boolean accept(MidiMessage message) {
        MessageType type = message.matchType();
        if (type == MessageType.NOTE_ON) {
            channels[message.channel() - 1][message.data1()] = true;
            return true;
        }
        if (type == MessageType.NOTE_OFF) {
            boolean[] channel = channels[message.channel() - 1];
            boolean wasHeld = channel[message.data1()];
            channel[message.data1()] = false;
            return wasHeld;
        }
        if (type == MessageType.CC
            && (message.data1() == 120
                || message.data1() == 123)) {
            Arrays.fill(channels[message.channel() - 1], false);
            return true;
        }
        return false;
    }

    public List<Integer> pitches() {
        return IntStream.range(0, 128)
            .filter(pitch -> IntStream.range(0, 16)
                .anyMatch(channel -> channels[channel][pitch]))
            .boxed()
            .toList();
    }
}
