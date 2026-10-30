package io.midirat.api;

import java.util.List;

import io.midirat.midi.MidiMessage;

public interface EmitterControls {
    String format();

    List<String> messages();

    void edit(String format, List<String> messages);

    List<MidiMessage> compile();
}
