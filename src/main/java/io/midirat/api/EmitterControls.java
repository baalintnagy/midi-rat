package io.midirat.api;

import java.util.List;

import io.midirat.midi.MidiMessage;

public interface EmitterControls {
    record Sequence(String format, List<String> messages) {
        public Sequence {
            messages = List.copyOf(messages);
        }
    }

    /** Override when state may change concurrently to return one coherent edit. */
    default Sequence snapshot() {
        return new Sequence(format(), messages());
    }

    String format();

    List<String> messages();

    void edit(String format, List<String> messages);

    List<MidiMessage> compile();
}
