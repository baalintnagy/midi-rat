package io.midirat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.midirat.api.ModuleInstance;
import io.midirat.config.NetworkConfig;
import io.midirat.config.NetworkConfig.Instance;
import io.midirat.host.RoutingEngine;
import io.midirat.midi.Endpoint;
import io.midirat.midi.Envelope;
import io.midirat.midi.MessageCodec;
import io.midirat.midi.MidiMessage;
import io.midirat.module.FilterModule;
import org.junit.jupiter.api.Test;

class RoutingTest {
    @Test
    void emitterUsesMessageThenDestinationOrder() {
        var first = new Endpoint("first", Endpoint.Kind.OUTPUT);
        var second = new Endpoint("second", Endpoint.Kind.OUTPUT);
        var submissions = new ArrayList<String>();
        ModuleInstance emitter = _ -> List.of();
        var config = new NetworkConfig(Map.of("first", first, "second", second), List.of(
            new Instance("emitter", "test", List.of(), List.of(first, second), emitter)));
        try (var engine = new RoutingEngine(config, (target, messages, time) -> {
            messages.forEach(m -> submissions.add("%s %s".formatted(target.name(), m.hex())));
        }, _ -> {
        }, 0)) {
            engine.prepare();
            engine.activate();
            engine.emit("emitter",
                List.of(MidiMessage.shortMessage(0xFA), MidiMessage.shortMessage(0xFC)));
            assertEquals(List.of("first FA", "second FA", "first FC", "second FC"), submissions);
        }
    }

    @Test
    void busBroadcastOrderIdentityAndOriginalTimestamp() throws Exception {
        var input = new Endpoint("input", Endpoint.Kind.INPUT);
        var bus = new Endpoint("bus", Endpoint.Kind.BUS);
        var received = new CopyOnWriteArrayList<Envelope>();
        var latch = new CountDownLatch(6);
        ModuleInstance observer = e -> {
            received.add(e);
            latch.countDown();
            return List.of();
        };
        var config = new NetworkConfig(Map.of("input", input, "bus", bus), List.of(
            new Instance("forward", "filter", List.of(input), List.of(bus),
                new FilterModule(List.of(), List.of())),
            new Instance("observe1", "monitor", List.of(bus), List.of(), observer),
            new Instance("observe2", "monitor", List.of(bus), List.of(), observer)));
        try (var engine = new RoutingEngine(config, (target, messages, time) -> {
        }, _ -> {
        }, 0)) {
            engine.prepare();
            engine.activate();
            for (int i = 0; i < 3; i++) {
                engine
                    .receive(new Envelope(input, 123 + i, MidiMessage.shortMessage(0x90, i, 100)));
            }
            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertEquals(6, received.size());
            assertTrue(received.stream()
                .allMatch(e -> e.origin()
                    .equals(input)));
            assertTrue(received.stream()
                .allMatch(e -> e.source()
                    .equals(bus)));
            assertTrue(received.stream()
                .allMatch(e -> e.receiptNanos() == 123 + e.message()
                    .data1()));
        }
    }

    @Test
    void panicSequenceAndNoAutomaticShutdownMessages() throws Exception {
        var bus = new Endpoint("bus", Endpoint.Kind.BUS);
        var received = new CopyOnWriteArrayList<MidiMessage>();
        var latch = new CountDownLatch(64);
        ModuleInstance monitor = e -> {
            received.add(e.message());
            latch.countDown();
            return List.of();
        };
        var config = new NetworkConfig(Map.of("bus", bus), List.of(
            new Instance("monitor", "monitor", List.of(bus), List.of(), monitor)));
        try (var engine = new RoutingEngine(config, (target, messages, time) -> {
        }, _ -> {
        }, 0)) {
            engine.prepare();
            engine.activate();
            assertTrue(received.isEmpty());
            engine.panicBuses(RoutingEngine.panicMessages(), 1);
            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("B0 40 00", "B0 42 00", "B0 7B 00", "B0 78 00"),
                received.subList(0, 4)
                    .stream()
                    .map(MidiMessage::hex)
                    .toList());
            assertEquals("BF 78 00", received.getLast()
                .hex());
        }
        assertEquals(64, received.size());
    }

    @Test
    void processingOverflowRejectsIncomingAndCounts() throws Exception {
        var input = new Endpoint("input", Endpoint.Kind.INPUT);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var errors = new CopyOnWriteArrayList<String>();
        ModuleInstance stalled = e -> {
            started.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread()
                    .interrupt();
            }
            return List.of();
        };
        var config = new NetworkConfig(Map.of("input", input), List.of(
            new Instance("stalled", "test", List.of(input), List.of(), stalled)));
        try (var engine = new RoutingEngine(config, (target, messages, time) -> {
        }, errors::add, 0)) {
            engine.prepare();
            engine.activate();
            var message = new Envelope(input, 1, MessageCodec.parse("hex", "F8"));
            engine.receive(message);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < RoutingEngine.PROCESSING_CAPACITY + 1; i++) {
                engine.receive(message);
            }
            assertTrue(engine.status()
                .getFirst()
                .contains("lost=1"));
            release.countDown();
        }
    }
}
