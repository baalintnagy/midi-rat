package io.midirat.module;

import java.util.List;

import io.midirat.api.ModuleInstance;
import io.midirat.midi.Envelope;
import io.midirat.midi.MidiMessage;
import io.midirat.rule.MessageRule;

public record FilterModule(List<MessageRule> whitelist,
    List<MessageRule> blacklist) implements ModuleInstance {
    public FilterModule {
        whitelist = List.copyOf(whitelist);
        blacklist = List.copyOf(blacklist);
    }

    @Override
    public List<MidiMessage> process(Envelope e) {
        boolean accepted = (whitelist.isEmpty()
            || whitelist.stream()
                .anyMatch(r -> r.matches(e)))
            && blacklist.stream()
                .noneMatch(r -> r.matches(e));
        return accepted ? List.of(e.message()) : List.of();
    }

    @Override
    public List<String> widgetLines() {
        return List.of("Filter: %d whitelist, %d blacklist rules".formatted(
            whitelist.size(), blacklist.size()));
    }
}
