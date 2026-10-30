package io.midirat.api;

import java.util.List;

import io.midirat.midi.Envelope;
import io.midirat.midi.MidiMessage;

/** API version 1. Calls to process are serialized; close must release owned resources. */
public interface ModuleInstance extends AutoCloseable {
    default void start(ModuleContext context) {
    }

    List<MidiMessage> process(Envelope message);

    default List<String> widgetLines() {
        return List.of();
    }

    @Override
    default void close() {
    }
}
