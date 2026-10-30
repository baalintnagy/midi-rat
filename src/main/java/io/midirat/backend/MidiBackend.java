package io.midirat.backend;

import java.util.List;
import java.util.function.Consumer;

import io.midirat.config.ApplicationConfig.PortBinding;
import io.midirat.midi.MidiMessage;

public interface MidiBackend extends AutoCloseable {
    record Received(MidiMessage message, long hostReceiptNanos) {
    }

    record Port(String key, String name, boolean input, boolean stableId, String capabilities) {
    }

    interface Input extends AutoCloseable {
        @Override
        void close();
    }

    interface Output extends AutoCloseable {
        void send(MidiMessage message);

        @Override
        void close();
    }

    List<Port> ports();

    Input openInput(Port port, Consumer<Received> receive);

    Output openOutput(Port port);

    default Port resolve(PortBinding binding, boolean input) {
        List<Port> matches = ports().stream()
            .filter(p -> p.input() == input)
            .filter(p -> switch (binding) {
            case PortBinding.Name name -> p.name()
                .equals(name.value());
            case PortBinding.Id id -> p.stableId()
                && p.key()
                    .equals(id.value());
            })
            .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException(
                """
                Binding %s resolves to %d %s ports; choose an exact name or \
                supported stable ID; inspect --ports\
                """
                    .formatted(binding, matches.size(), input ? "input" : "output"));
        }
        return matches.getFirst();
    }

    @Override
    default void close() {
    }
}
