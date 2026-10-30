package io.midirat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.midirat.config.NetworkConfig;
import io.midirat.config.NetworkConfig.Instance;
import io.midirat.host.RoutingEngine;
import io.midirat.midi.Endpoint;
import io.midirat.midi.Envelope;
import io.midirat.midi.MidiMessage;
import io.midirat.module.FilterModule;
import io.midirat.module.MonitorModule;
import org.junit.jupiter.api.Test;

class MessageOriginTest {
    @Test
    void multipleBusesPreserveOriginWhileSubscriptionsUseImmediateSource() throws Exception {
        var input = new Endpoint("keyboard", Endpoint.Kind.INPUT);
        var first = new Endpoint("first", Endpoint.Kind.BUS);
        var second = new Endpoint("second", Endpoint.Kind.BUS);
        var received = new ArrayBlockingQueue<Envelope>(1);
        var config = new NetworkConfig(Map.of("keyboard", input, "first", first, "second", second),
            List.of(new Instance("first-hop", "filter", List.of(input), List.of(first),
                new FilterModule(List.of(), List.of())),
                new Instance("second-hop", "filter", List.of(first), List.of(second),
                    new FilterModule(List.of(), List.of())),
                new Instance("observe", "monitor", List.of(second), List.of(), envelope -> {
                    received.add(envelope);
                    return List.of();
                })));
        try (var engine = new RoutingEngine(config, (target, messages, receipt) -> {
        }, _ -> {
        }, 0)) {
            engine.prepare();
            engine.activate();
            var message = MidiMessage.shortMessage(0x90, 61, 100);
            engine.receive(new Envelope(input, 123, message, input, "MIDI Keyboard Input"));
            var result = received.poll(2, TimeUnit.SECONDS);
            assertEquals(new Envelope(second, 123, message, input, "MIDI Keyboard Input"), result);
            var monitor = new MonitorModule(20, true);
            monitor.process(result);
            monitor.toggleDetails();
            var rows = monitor.widgetLines();
            assertTrue(rows.get(1)
                .contains("MIDI Keyboard Input | second"));
            assertTrue(rows.get(2)
                .contains("via: [second]"));
        }
    }
}
