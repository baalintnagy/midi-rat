package io.midirat.module;

import java.util.List;
import java.util.stream.Stream;

import io.midirat.api.ModuleInstance;
import io.midirat.midi.Envelope;
import io.midirat.midi.MessageCodec;
import io.midirat.midi.MidiMessage;

public final class EmitterModule implements ModuleInstance {
    private String format;
    private List<String> messages;

    public EmitterModule(String format, List<String> messages) {
        edit(format, messages);
    }

    public String format() {
        return format;
    }

    public List<String> messages() {
        return messages;
    }

    public void edit(String nextFormat, List<String> nextMessages) {
        if (!nextFormat.equals("hex")
            && !nextFormat.equals("message-text")) {
            throw new IllegalArgumentException("format must be hex or message-text");
        }
        if (!nextMessages.isEmpty()) {
            MessageCodec.sequence(nextFormat, nextMessages);
        }
        format = nextFormat;
        messages = List.copyOf(nextMessages);
    }

    public List<MidiMessage> compile() {
        return MessageCodec.sequence(format, messages);
    }

    @Override
    public List<MidiMessage> process(Envelope e) {
        return List.of();
    }

    @Override
    public List<String> widgetLines() {
        return Stream.concat(
            Stream.of("Emitter (%s): %d messages; Enter sends, s saves, e edits"
                .formatted(format, messages.size())),
            messages.stream())
            .toList();
    }
}
