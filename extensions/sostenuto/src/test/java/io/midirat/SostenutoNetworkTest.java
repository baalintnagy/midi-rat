package io.midirat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.midirat.api.ModuleInstance;
import io.midirat.config.ApplicationConfig;
import io.midirat.config.NetworkConfig;
import io.midirat.config.NetworkConfig.Instance;
import io.midirat.host.RoutingEngine;
import io.midirat.midi.Envelope;
import io.midirat.midi.MessageCodec;
import io.midirat.module.Builtins;
import io.midirat.sostenuto.SostenutoProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SostenutoNetworkTest {
    private NetworkConfig network(String application) throws Exception {
        return NetworkConfig.read(ApplicationConfig.read(Path.of("config", application)),
            Stream.concat(Builtins.providers()
                .stream(), Stream.of(new SostenutoProvider()))
                .collect(Collectors.toMap(p -> p.type(), p -> p)));
    }

    @ParameterizedTest
    @ValueSource(
        strings = { "wmm-application-sostenuto.yaml", "wmm-application-sostenuto-launchpad.yaml" })
    void sharedFilterAcceptsMusicalMessagesFromEveryInputAndDropsSystemTraffic(String application)
        throws Exception {
        var network = network(application);
        try {
            var filter = network.instances()
                .getFirst();
            assertEquals(8, filter.sources()
                .size());
            for (var source : filter.sources()) {
                for (String hex : List.of("90 3C 64", "80 3C 2D", "90 3C 00", "B0 40 7F",
                    "E0 00 40", "D0 20", "A0 3C 40", "C0 01")) {
                    var message = MessageCodec.parse("hex", hex);
                    assertEquals(List.of(message), filter.module()
                        .process(new Envelope(source, 1, message)));
                }
                for (String hex : List.of("F8", "F0 01 F7", "FA", "FB", "FC", "F1 01",
                    "F2 00 01", "F3 01", "F6", "FE", "FF")) {
                    assertEquals(List.of(), filter.module()
                        .process(new Envelope(source, 1,
                            MessageCodec.parse("hex", hex))));
                }
            }
        } finally {
            network.instances()
                .forEach(instance -> instance.module()
                    .close());
        }
    }

    @Test
    void launchpadOriginControlsSostenutoAfterMergeWhileMonitorSeesRawMusicalInput()
        throws Exception {
        var prepared = network("wmm-application-sostenuto-launchpad.yaml");
        var raw = new CopyOnWriteArrayList<String>();
        var chords = new CopyOnWriteArrayList<String>();
        var physical = new CopyOnWriteArrayList<String>();
        var errors = new CopyOnWriteArrayList<String>();
        var rawDone = new CountDownLatch(7);
        var chordDone = new CountDownLatch(4);
        var physicalDone = new CountDownLatch(4);
        ModuleInstance monitor = message -> {
            assertEquals("musical-input", message.source()
                .name());
            raw.add(message.message()
                .hex());
            rawDone.countDown();
            return List.of();
        };
        ModuleInstance chord = message -> {
            assertEquals("sostenuto-output", message.source()
                .name());
            chords.add(message.message()
                .hex());
            chordDone.countDown();
            return List.of();
        };
        var instances = prepared.instances()
            .stream()
            .map(instance -> {
                ModuleInstance replacement = switch (instance.type()) {
                case "midi-monitor" -> monitor;
                case "midi-chord" -> chord;
                default -> instance.module();
                };
                return new Instance(instance.name(), instance.type(), instance.sources(),
                    instance.destinations(), replacement);
            })
            .toList();
        var config = new NetworkConfig(prepared.endpoints(), instances);
        try (var engine = new RoutingEngine(config, (target, messages, _) -> {
            assertEquals("VirtOut", target.name());
            messages.forEach(message -> {
                physical.add(message.hex());
                physicalDone.countDown();
            });
        }, errors::add, 0)) {
            engine.prepare();
            engine.activate();
            for (String event : List.of("hammer-main/90 4E 5A", "launchpad-main/90 4E 64",
                "hammer-main/80 4E 00", "hammer-main/90 3C 64", "hammer-main/80 3C 20",
                "launchpad-main/80 4E 00", "launchpad-main/90 4E 64", "motu/F8",
                "hammer-main/F0 01 F7", "launchpad-main/FA")) {
                String[] parts = event.split("/", 2);
                engine.receive(new Envelope(config.endpoints()
                    .get(parts[0]), 123,
                    MessageCodec.parse("hex", parts[1])));
            }
            assertTrue(rawDone.await(2, TimeUnit.SECONDS));
            assertTrue(chordDone.await(2, TimeUnit.SECONDS));
            assertTrue(physicalDone.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("90 4E 5A", "90 4E 64", "80 4E 00", "90 3C 64",
                "80 3C 20", "80 4E 00", "90 4E 64"), raw);
            assertEquals(List.of("90 4E 5A", "90 3C 64", "80 3C 20", "80 4E 00"), physical);
            assertEquals(physical, chords);
            assertEquals(List.of(), errors);
        } finally {
            prepared.instances()
                .forEach(instance -> instance.module()
                    .close());
        }
    }
}
