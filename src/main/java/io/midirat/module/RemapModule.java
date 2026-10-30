package io.midirat.module;

import java.util.ArrayList;
import java.util.List;

import io.midirat.api.ModuleInstance;
import io.midirat.midi.Envelope;
import io.midirat.midi.MidiMessage;
import io.midirat.rule.MessageRule;

public record RemapModule(List<Rule> rules) implements ModuleInstance {
    private static final int MAX_BRANCHES = 4096;

    public record Rule(MessageRule match, Action action, boolean stop) {
    }

    private record Branch(MidiMessage message, boolean finished) {
    }

    public RemapModule {
        rules = List.copyOf(rules);
    }

    @Override
    public List<MidiMessage> process(Envelope e) {
        List<Branch> branches = List.of(new Branch(e.message(), false));
        for (Rule rule : rules) {
            List<Branch> next = new ArrayList<>();
            for (Branch branch : branches) {
                Envelope current = e.forward(e.source(), branch.message());
                if (branch.finished()
                    || !rule.match()
                        .matches(current)) {
                    next.add(branch);
                } else {
                    for (MidiMessage result : rule.action()
                        .apply(current)) {
                        next.add(new Branch(result, rule.stop()));
                        if (next.size() > MAX_BRANCHES) {
                            throw new IllegalStateException(
                                "Remap expansion exceeds 4096 branches");
                        }
                    }
                }
                if (next.size() > MAX_BRANCHES) {
                    throw new IllegalStateException("Remap expansion exceeds 4096 branches");
                }
            }
            branches = next;
        }
        return branches.stream()
            .map(Branch::message)
            .toList();
    }

    @Override
    public List<String> widgetLines() {
        return List.of("Remap: %d ordered rules".formatted(rules.size()));
    }
}
