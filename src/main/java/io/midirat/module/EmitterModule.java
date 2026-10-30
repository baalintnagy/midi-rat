package io.midirat.module;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import io.midirat.api.EmitterControls;
import io.midirat.api.EmitterControls.Sequence;
import io.midirat.api.ModuleInstance;
import io.midirat.midi.Envelope;
import io.midirat.midi.MessageCodec;
import io.midirat.midi.MidiMessage;

public final class EmitterModule implements ModuleInstance, EmitterControls {
    @Override
    public Optional<EmitterControls> emitterControls() {
        return Optional.of(this);
    }

    private volatile Sequence sequence;

    public EmitterModule(String format, List<String> messages) {
        edit(format, messages);
    }

    public String format() {
        return sequence.format();
    }

    public List<String> messages() {
        return sequence.messages();
    }

    @Override
    public Sequence snapshot() {
        return sequence;
    }

    public void edit(String nextFormat, List<String> nextMessages) {
        if (!nextFormat.equals("hex")
            && !nextFormat.equals("message-text")) {
            throw new IllegalArgumentException("format must be hex or message-text");
        }
        Sequence next = new Sequence(nextFormat, nextMessages);
        if (!next.messages()
            .isEmpty()) {
            MessageCodec.sequence(next.format(), next.messages());
        }
        sequence = next;
    }

    public List<MidiMessage> compile() {
        Sequence current = snapshot();
        return MessageCodec.sequence(current.format(), current.messages());
    }

    @Override
    public List<MidiMessage> process(Envelope e) {
        return List.of();
    }

    @Override
    public List<String> widgetLines() {
        Sequence current = snapshot();
        return Stream.concat(
            Stream.of("Emitter (%s): %d messages; Enter sends, s saves, e edits"
                .formatted(current.format(), current.messages()
                    .size())),
            current.messages()
                .stream())
            .toList();
    }
}
