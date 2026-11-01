package io.midirat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.midirat.api.ModuleContext;
import io.midirat.config.Value;
import io.midirat.midi.Endpoint;
import io.midirat.midi.Envelope;
import io.midirat.midi.MessageCodec;
import io.midirat.midi.MidiMessage;
import io.midirat.sostenuto.SostenutoDecoder;
import io.midirat.sostenuto.SostenutoModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SostenutoTest {
    private final Endpoint keys = new Endpoint("keys", Endpoint.Kind.INPUT);
    private final Endpoint pedals = new Endpoint("pedals", Endpoint.Kind.INPUT);
    @TempDir
    Path directory;

    private SostenutoModule module(String yaml) throws Exception {
        Path file = directory.resolve("settings.yaml");
        Files.writeString(file, yaml);
        return new SostenutoModule(
            SostenutoDecoder.decode(Value.read(file), List.of(keys, pedals)));
    }

    private List<String> send(SostenutoModule module, String hex) {
        return send(module, keys, hex);
    }

    private List<String> send(SostenutoModule module, Endpoint source, String hex) {
        return module.process(new Envelope(source, 99, MessageCodec.parse("hex", hex), keys,
            "Hardware keyboard"))
            .stream()
            .map(MidiMessage::hex)
            .toList();
    }

    @Test
    void passesOrdinaryMidiAndOriginalReleasePayloadsThrough() throws Exception {
        try (var module = module("{}")) {
            for (String message : List.of("90 3C 64", "80 3C 2D", "90 3C 00", "B0 01 03",
                "C0 04", "E0 01 02", "F8", "F0 01 02 F7")) {
                assertEquals(List.of(message), send(module, message));
            }
        }
    }

    @Test
    void sustainRetainsReleasesAndUsesTheStandardThreshold() throws Exception {
        try (var module = module("{}")) {
            send(module, "90 3C 64");
            assertEquals(List.of(), send(module, "B0 40 40"));
            assertEquals(List.of(), send(module, "90 3C 00"));
            assertEquals(List.of(), send(module, "B0 40 7F"));
            assertEquals(List.of("80 3C 00"), send(module, "B0 40 3F"));
            assertEquals(List.of(), send(module, "B0 40 00"));
        }
    }

    @Test
    void sostenutoCapturesOnlyItsEngagementSnapshotAndHasHysteresis() throws Exception {
        try (var module = module("{}")) {
            send(module, "90 3C 64");
            send(module, "B0 42 09");
            send(module, "B0 42 0A");
            send(module, "90 3D 64");
            send(module, "B0 42 7F");
            assertEquals(List.of(), send(module, "80 3C 00"));
            assertEquals(List.of("80 3D 2D"), send(module, "80 3D 2D"));
            assertEquals(List.of(), send(module, "B0 42 09"));
            assertEquals(List.of("80 3C 00"), send(module, "B0 42 08"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void handsRetainedNotesBetweenPedalsWithoutReleasing(boolean sustainFirst) throws Exception {
        try (var module = module("{}")) {
            String first = sustainFirst ? "40" : "42";
            String second = sustainFirst ? "42" : "40";
            send(module, "90 3C 64");
            send(module, "B0 %s 7F".formatted(first));
            send(module, "80 3C 00");
            send(module, "B0 %s 7F".formatted(second));
            assertEquals(List.of(), send(module, "B0 %s 00".formatted(first)));
            assertEquals(List.of("80 3C 00"), send(module, "B0 %s 00".formatted(second)));
        }
    }

    @Test
    void repressedKeysWaitForTheirOwnReleaseAndChannelsAreIndependent() throws Exception {
        try (var module = module("{}")) {
            send(module, "90 3C 64");
            send(module, "B0 40 7F");
            send(module, "80 3C 00");
            send(module, "90 3C 65");
            assertEquals(List.of("81 3C 20"), send(module, "81 3C 20"));
            assertEquals(List.of(), send(module, "B0 40 00"));
            assertEquals(List.of("80 3C 20"), send(module, "80 3C 20"));
        }
    }

    @Test
    void dedicatedControlSourceConsumesOnlyItsTriggerAndKeepsPrimaryCcUnchanged() throws Exception {
        try (var module = module("controlSources: [pedals]")) {
            assertEquals(List.of("B0 16 7F"), send(module, "B0 16 7F"));
            assertEquals(List.of(), send(module, pedals, "90 3C 64"));
            assertEquals(List.of(), send(module, pedals, "B0 01 01"));
            send(module, "90 3C 64");
            send(module, pedals, "B0 16 7F");
            assertEquals(List.of(), send(module, "80 3C 00"));
            assertEquals(List.of("80 3C 00"), send(module, pedals, "B0 16 00"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "{cc: 22, toggle: true}", "{note: 78, toggle: true}" })
    void toggleChangesOncePerPressAndReleaseOnlyRearms(String trigger) throws Exception {
        try (var module = module("trigger: %s".formatted(trigger))) {
            boolean note = trigger.contains("note");
            String press = note ? "90 4E 01" : "B0 16 0A";
            String release = note ? "90 4E 00" : "B0 16 08";
            send(module, "90 3C 64");
            send(module, press);
            assertEquals(List.of(), send(module, press));
            assertEquals(List.of(), send(module, release));
            assertEquals(List.of(), send(module, "80 3C 00"));
            assertEquals(List.of("80 3C 00"), send(module, press));
        }
    }

    @Test
    void invertedCcAndLowVelocityMomentaryNoteTriggersWork() throws Exception {
        for (String trigger : List.of("{cc: 22, invert: true}", "{note: 78}")) {
            try (var module = module("trigger: %s".formatted(trigger))) {
                boolean note = trigger.contains("note");
                send(module, "90 3C 64");
                assertEquals(List.of(), send(module, note ? "90 4E 01" : "B0 16 00"));
                assertEquals(List.of(), send(module, "80 3C 00"));
                assertEquals(List.of("80 3C 00"),
                    send(module, note ? "80 4E 7F" : "B0 16 7F"));
            }
        }
    }

    @Test
    void explicitResetsAffectOneChannelAndControllerResetKeepsPressedKeys() throws Exception {
        try (var module = module("{}")) {
            send(module, "90 3C 64");
            send(module, "90 3D 64");
            send(module, "91 40 64");
            send(module, "B0 40 7F");
            send(module, "B1 40 7F");
            send(module, "80 3C 00");
            send(module, "81 40 00");
            assertEquals(List.of("80 3C 00", "B0 79 00"), send(module, "B0 79 00"));
            assertEquals(List.of("80 3D 20"), send(module, "80 3D 20"));
            assertEquals(List.of("B1 7B 00"), send(module, "B1 7B 00"));
            assertEquals(List.of(), send(module, "B1 40 00"));
        }
    }

    @Test
    void umbrellaOrdersPulseAndDelayedNoteAndPreservesInputProvenance() throws Exception {
        var context = new RecordingContext(4);
        try (var module = module("umbrella: {enabled: true, delayMs: 0, tailMs: 0}")) {
            module.start(context);
            send(module, "90 3C 5A");
            send(module, "B0 40 7F");
            send(module, "80 3C 00");
            send(module, "90 3C 64");
            assertTrue(context.latch.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("90 3C 5A", "B0 40 7F", "90 3C 64", "B0 40 00"), context.hex());
            assertTrue(context.inputs.stream()
                .allMatch(input -> input.origin()
                    .equals(keys)
                    && input.originName()
                        .equals("Hardware keyboard")
                    && input.receiptNanos() == 99));
            assertEquals(List.of(), context.errors);
        }
    }

    @Test
    void earlyReleaseCancelsDelayedRetriggerAndCloseDoesNotEmitCleanup() throws Exception {
        var context = new RecordingContext(0);
        var module = module("umbrella: {enabled: true, delayMs: 60000, tailMs: 0}");
        try {
            module.start(context);
            send(module, "90 3C 5A");
            send(module, "B0 40 7F");
            send(module, "80 3C 00");
            send(module, "90 3C 64");
            send(module, "B0 40 00");
            send(module, "80 3C 2D");
            assertEquals(List.of("90 3C 5A", "B0 40 7F", "B0 40 00", "80 3C 2D"), context.hex());
        } finally {
            module.close();
        }
        assertEquals(4, context.hex()
            .size());
        assertEquals(List.of(), send(module, "90 3C 64"));
    }

    @Test
    void explicitResetCancelsPendingUmbrellaAndReleasesOnlyItsPulse() throws Exception {
        var context = new RecordingContext(0);
        try (var module = module("umbrella: {enabled: true, delayMs: 60000, tailMs: 0}")) {
            module.start(context);
            send(module, "90 3C 5A");
            send(module, "B0 40 7F");
            send(module, "80 3C 00");
            send(module, "90 3C 64");
            send(module, "B0 7B 00");
            assertEquals(List.of("90 3C 5A", "B0 40 7F", "B0 40 00", "B0 7B 00"), context.hex());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "{unknown: true}", "{activationThreshold: 0}", "{hysteresis: -1}",
            "{controlSources: []}", "{controlSources: [missing]}", "{controlSources: [keys, keys]}",
            "{trigger: {cc: 22, note: 78}}", "{trigger: {note: 78, invert: true}}",
            "{trigger: {cc: 128}}", "{trigger: {toggle: 1}}", "{umbrella: {delayMs: -1}}",
            "{umbrella: {tailMs: 60001}}", "{umbrella: {unknown: true}}" })
    void rejectsInvalidSettings(String yaml) {
        assertThrows(IllegalArgumentException.class, () -> module(yaml));
    }

    @Test
    void umbrellaQueueIsBoundedAndOverflowIsReported() throws Exception {
        var context = new RecordingContext(0);
        try (var module = module("umbrella: {enabled: true, delayMs: 60000, tailMs: 0}")) {
            module.start(context);
            send(module, "90 3C 5A");
            send(module, "B0 40 7F");
            send(module, "80 3C 00");
            for (int i = 0; i < 4097; i++) {
                send(module, "90 3C 64");
            }
            assertEquals(List.of("90 3C 5A", "B0 40 7F", "90 3C 64"), context.hex());
            assertEquals(List.of("Umbrella queue full; retrigger passed without protection"),
                context.errors);
        }
    }

    @Test
    void originSelectorsRejectUnknownEndpointsAtStartAndInvalidShapesAtPreparation()
        throws Exception {
        try (var module = module("controlOrigins: [missing]")) {
            assertThrows(IllegalArgumentException.class,
                () -> module.start(new RecordingContext(0)));
        }
        for (String yaml : List.of("controlOrigins: []", "controlOrigins: [keys, keys]",
            "{controlSources: [keys], controlOrigins: [keys]}")) {
            assertThrows(IllegalArgumentException.class, () -> module(yaml));
        }
    }

    private static final class RecordingContext implements ModuleContext {
        private final List<Envelope> inputs = new CopyOnWriteArrayList<>();
        private final List<MidiMessage> messages = new CopyOnWriteArrayList<>();
        private final List<String> errors = new CopyOnWriteArrayList<>();
        private final CountDownLatch latch;

        RecordingContext(int expected) {
            latch = new CountDownLatch(expected);
        }

        @Override
        public void publish(Endpoint target, List<MidiMessage> result) {
            throw new AssertionError("Use provenance-preserving forwarding");
        }

        @Override
        public void forward(Envelope input, List<MidiMessage> result) {
            result.forEach(message -> {
                inputs.add(input);
                messages.add(message);
                latch.countDown();
            });
        }

        @Override
        public void report(String diagnostic) {
            errors.add(diagnostic);
        }

        List<String> hex() {
            return messages.stream()
                .map(MidiMessage::hex)
                .toList();
        }
    }
}
