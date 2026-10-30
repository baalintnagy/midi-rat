package io.midirat.module;

import java.util.List;

import io.midirat.api.ModuleInstance;
import io.midirat.midi.Envelope;
import io.midirat.midi.MidiMessage;

public record UnavailableModule(String reason) implements ModuleInstance {
    @Override
    public List<MidiMessage> process(Envelope envelope) {
        return List.of();
    }

    @Override
    public List<String> widgetLines() {
        return List.of("UNAVAILABLE: %s".formatted(reason));
    }
}
