package io.midirat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import io.midirat.backend.MidiBackend;
import io.midirat.config.ApplicationConfig;
import io.midirat.config.EmitterPersistence;
import io.midirat.config.NetworkConfig;
import io.midirat.host.DeviceManager;
import io.midirat.host.Diagnostics;
import io.midirat.host.Host;
import io.midirat.host.RoutingEngine;
import io.midirat.midi.MidiMessage;
import io.midirat.module.Builtins;
import io.midirat.module.EmitterModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostTest {
    @TempDir
    Path directory;

    private static final class Backend implements MidiBackend {
        private int opened;
        private int closed;
        private final List<MidiMessage> sent = new CopyOnWriteArrayList<>();
        private final CountDownLatch panic = new CountDownLatch(64);

        @Override
        public List<Port> ports() {
            return List.of(new Port("out-id", "Exact Output", false, true, "synthetic"));
        }

        @Override
        public Input openInput(Port port, Consumer<Received> receive) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Output openOutput(Port port) {
            opened++;
            return new Output() {
                @Override
                public void send(MidiMessage message) {
                    sent.add(message);
                    panic.countDown();
                }

                @Override
                public void close() {
                    closed++;
                }
            };
        }
    }

    ApplicationConfig config(String outputs) throws Exception {
        Path file = directory.resolve("application.yaml");
        Files.writeString(file,
            """
            schemaVersion: 1
            application:
              backend: java-sound
              networkFile: network.yaml
              ports:
                outputs: %s
            """
                .formatted(outputs));
        Files.writeString(directory.resolve("network.yaml"),
            """
            schemaVersion: 1
            network:
              buses: [bus]
              modules:
                - name: emitter
                  type: midi-emitter
                  destinations: [bus]
                  settings: {messages: ["90 3C 64"]}
                - name: monitor
                  type: midi-monitor
                  sources: [bus]
                  settings: {historyLimit: 012}
            """
        );
        return ApplicationConfig.read(file);
    }

    @Test
    void aliasesShareOneHandleAndPanicBypassesModules() throws Exception {
        var backend = new Backend();
        var app = config("{synth: Exact Output, other: {id: out-id}}");
        try (var devices = new DeviceManager(backend, app, _ -> {
        }, System.nanoTime())) {
            devices.connect();
            assertEquals(1, backend.opened);
            devices.panic(RoutingEngine.panicMessages(), 0);
            assertTrue(backend.panic.await(2, TimeUnit.SECONDS));
            assertEquals(RoutingEngine.panicMessages(), backend.sent);
        }
        assertEquals(1, backend.closed);
        assertEquals(64, backend.sent.size());
    }

    @Test
    void exactCaseAndAmbiguityAndIdCapabilities() {
        var backend = new Backend();
        assertEquals("out-id",
            backend.resolve(new ApplicationConfig.PortBinding.Id("out-id"), false)
                .key());
        assertThrows(IllegalArgumentException.class,
            () -> backend.resolve(new ApplicationConfig.PortBinding.Name("exact output"), false));
        assertThrows(IllegalArgumentException.class,
            () -> backend.resolve(new ApplicationConfig.PortBinding.Name("Exact Output"), true));
    }

    @Test
    void configurationDetailsDescribeLoadedFilesAndRetainTheActiveNetworkSnapshot()
        throws Exception {
        var app = config("{synth: {id: out-id}}");
        try (var diagnostics = new Diagnostics(directory.resolve("logs"));
            var host = new Host(app, new Backend(), diagnostics, System.nanoTime())) {
            var details = host.configuration();
            assertEquals(app, details.application());
            String text = String.join("\n", details.applicationYaml());
            assertTrue(text.contains("id: out-id"));
            text = String.join("\n", details.networkYaml());
            assertTrue(text.contains("name: emitter"));
            assertTrue(text.contains("destinations: [bus]"));
            assertTrue(details.providers()
                .stream()
                .anyMatch(provider -> provider.type()
                    .equals("midi-message-filter")
                    && provider.apiVersion() == 1));
            Files.writeString(app.network(), "external edit not yet loaded");
            assertEquals(details, host.configuration());
        }
    }

    @Test
    void invalidReloadKeepsActiveInstancesAndSaveDetectsConflict() throws Exception {
        var app = config("{}");
        try (var diagnostics = new Diagnostics(directory.resolve("logs"));
            var host = new Host(app, new Backend(), diagnostics, System.nanoTime())) {
            var before = host.instances()
                .getFirst();
            String valid = Files.readString(app.network());
            Files.writeString(app.network(),
                "schemaVersion: 1\nnetwork: {modules: [{name: invalid}]}");
            assertThrows(IllegalArgumentException.class, host::reload);
            assertSame(before.module(), host.instances()
                .getFirst()
                .module());
            assertThrows(java.io.IOException.class, () -> host.save(before));
            Files.writeString(app.network(), valid);
            host.send(before);
            host.reload();
            assertEquals(2, host.instances()
                .size());
        }
    }

    @Test
    void savePreservesOtherSettingsAndIntegerMeaning() throws Exception {
        var app = config("{}");
        byte[] expected = Files.readAllBytes(app.network());
        var emitter = new EmitterModule("message-text", List.of("CH:1|PC|D1:16|D2:0|Status:192"));
        EmitterPersistence.save(app.network(), expected, "emitter", emitter);
        var providers = Builtins.providers()
            .stream()
            .collect(java.util.stream.Collectors.toMap(p -> p.type(), p -> p));
        var network = NetworkConfig.read(app, providers);
        assertTrue(network.instances()
            .get(1)
            .module()
            .widgetLines()
            .getFirst()
            .contains("/12"));
        assertEquals("message-text", ((EmitterModule) network.instances()
            .getFirst()
            .module()).format());
        assertTrue(Files.readString(app.network())
            .contains("historyLimit: 12"));
    }
}
