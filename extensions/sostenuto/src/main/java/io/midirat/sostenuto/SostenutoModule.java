package io.midirat.sostenuto;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.midirat.api.ModuleContext;
import io.midirat.api.ModuleInstance;
import io.midirat.midi.Envelope;
import io.midirat.midi.MessageType;
import io.midirat.midi.MidiMessage;
import io.midirat.sostenuto.SostenutoState.Status;

/** Adapter for input mapping, the pedal state machine, and optional timed forwarding. */
public final class SostenutoModule implements ModuleInstance {
    private final SostenutoSettings settings;
    private final SostenutoState state;
    private final SostenutoTrigger trigger;
    private volatile List<String> lines;
    private Umbrella umbrella;
    private boolean closed;

    public SostenutoModule(SostenutoSettings settings) {
        this.settings = settings;
        state = new SostenutoState(settings);
        trigger = new SostenutoTrigger(settings);
        updateDisplay();
    }

    @Override
    public synchronized void start(ModuleContext context) {
        if (closed
            || umbrella != null) {
            throw new IllegalStateException("Sostenuto instance is closed or already started");
        }
        List<String> knownOrigins = context.originEndpoints()
            .stream()
            .map(endpoint -> endpoint.name())
            .toList();
        for (String origin : settings.controlOrigins()) {
            if (!knownOrigins.contains(origin)) {
                throw new IllegalArgumentException("Unknown control origin: %s".formatted(origin));
            }
        }
        if (settings.umbrella()
            .enabled()) {
            umbrella = new Umbrella(context, settings.umbrella());
        }
    }

    @Override
    public synchronized List<MidiMessage> process(Envelope input) {
        if (closed) {
            return List.of();
        }
        Optional<MidiMessage> mapped = trigger.map(input);
        if (mapped.isEmpty()) {
            return List.of();
        }
        MidiMessage message = mapped.get();
        boolean protect = settings.umbrella()
            .enabled()
            && state.protects(message);
        List<MidiMessage> output = new ArrayList<>();
        if (message.type() == MessageType.CC
            && (message.data1() == 120
                || message.data1() == 121
                || message.data1() == 123)) {
            int channel = message.channel() - 1;
            trigger.reset(channel);
            if (umbrella != null) {
                output.addAll(umbrella.reset(channel));
            }
        }
        output.addAll(state.process(message));
        updateDisplay();
        if (!settings.umbrella()
            .enabled()) {
            return List.copyOf(output);
        }
        if (umbrella == null) {
            throw new IllegalStateException("Start sostenuto before using umbrella mode");
        }
        if (protect) {
            umbrella.protect(input, message);
        } else {
            umbrella.forward(input, output);
        }
        return List.of();
    }

    private void updateDisplay() {
        Status status = state.status();
        lines = List.of(
            "Sustain channels: %d | Sostenuto channels: %d | Umbrella: %s".formatted(
                status.sustain(), status.sostenuto(), settings.umbrella()
                    .enabled() ? "on" : "off"),
            "Pressed: %d | Latched: %d | Retained note-offs: %d".formatted(
                status.pressed(), status.latched(), status.deferred()));
    }

    @Override
    public List<String> widgetLines() {
        return lines;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (umbrella != null) {
            umbrella.close();
        }
    }
}
