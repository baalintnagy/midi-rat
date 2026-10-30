package io.midirat.api;

import java.util.List;
import java.util.Optional;

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

    default ModuleWidget widget() {
        return ModuleWidget.fixed(this::widgetLines);
    }

    default Optional<EmitterControls> emitterControls() {
        return Optional.empty();
    }

    default Optional<MonitorControls> monitorControls() {
        return Optional.empty();
    }

    @Override
    default void close() {
    }
}
