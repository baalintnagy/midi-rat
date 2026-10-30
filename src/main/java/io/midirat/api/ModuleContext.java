package io.midirat.api;

import java.util.List;

import io.midirat.midi.Endpoint;
import io.midirat.midi.Envelope;
import io.midirat.midi.MidiMessage;

public interface ModuleContext {
    /** Logical endpoints that may originate traffic in this network. */
    default List<Endpoint> originEndpoints() {
        return List.of();
    }

    default SourceNames sourceNames() {
        return SourceNames.empty();
    }

    void publish(Endpoint destination, List<MidiMessage> messages);

    /** Forward delayed processing results to declared destinations with input provenance. */
    default void forward(Envelope input, List<MidiMessage> messages) {
        throw new UnsupportedOperationException("Delayed forwarding is unavailable");
    }

    void report(String diagnostic);

    default void report(ModuleError error) {
        report("%s: %s %s".formatted(error.code(), error.message(), error.context()));
    }
}
