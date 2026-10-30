package io.midirat.api;

import java.util.List;

import io.midirat.midi.Endpoint;
import io.midirat.midi.MidiMessage;

public interface ModuleContext {
    default SourceNames sourceNames() {
        return SourceNames.empty();
    }

    void publish(Endpoint destination, List<MidiMessage> messages);

    void report(String diagnostic);

    default void report(ModuleError error) {
        report("%s: %s %s".formatted(error.code(), error.message(), error.context()));
    }
}
