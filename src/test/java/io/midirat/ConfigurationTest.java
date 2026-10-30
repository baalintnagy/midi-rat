package io.midirat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.midirat.config.ApplicationConfig;
import io.midirat.config.NetworkConfig;
import io.midirat.config.Value;
import io.midirat.midi.Endpoint;
import io.midirat.midi.Envelope;
import io.midirat.midi.MessageCodec;
import io.midirat.module.Builtins;
import io.midirat.rule.RuleDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigurationTest {
    @TempDir
    Path directory;
    private final Endpoint keyboard = new Endpoint("keyboard", Endpoint.Kind.INPUT);

    Value yaml(String text) throws Exception {
        Path file = directory.resolve("fragment.yaml");
        Files.writeString(file, text);
        return Value.read(file);
    }

    @ParameterizedTest
    @ValueSource(strings = { "{type: CLOCK, channels: [1]}", "{types: [CC]}", "{type: [CC]}",
            "{type: CC, notes: [60]}", "{type: PC, data2: 0}", "{type: SYSEX, data1: 0}",
            "{type: CC, value: 128}", "{type: P_BEND, value: 0x4000}",
            "{type: NOTE, channels: [0]}",
            "{type: CC, value: '0x40'}", "{type: CC, value: 1.0}", "{type: CC, value: true}",
            "{type: CC, ccs: [64, {min: 1, max: 2}]}", "{type: NOTE, notes: []}",
            "{type: CC, value: {min: 20, max: 10}}", "{type: CC, value: {min: 0}}",
            "{type: CC, sources: keyboard}", "{type: CC, sources: []}",
            "{type: CC, sources: [pedals]}", "{type: NOTE_ON, channels: [1], statuses: [0x91]}",
            "{type: CC, value: 999999999999999999999}", "{type: CC, value: null}",
            "{type: CC, type: NOTE}", "{type: CC, value: 0b10}", "{type: CC, value: 1_000}",
            "{type: CC, ccs: [64], data1: 65}", "{type: CC, value: 0, data2: 1}",
            "{type: P_BEND, value: 8192, data1: 1}", "{type: NOTE_ON, velocity: 0}",
            "{type: NOTE_OFF, statuses: [0x90], velocity: 1}" })
    void invalidRulesHaveLocations(String text) throws Exception {
        var value = yaml(text);
        var error = assertThrows(IllegalArgumentException.class,
            () -> RuleDecoder.decode(value, List.of(keyboard)));
        assertTrue(error.getMessage()
            .contains("line"), error.getMessage());
    }

    @Test
    void numericEquivalenceAndImmediateSource() throws Exception {
        var decimal = RuleDecoder.decode(yaml(
            """
            {type: CC, channels: [1], ccs: [64],
             value: {min: 64, max: 127}, sources: [keyboard]}
            """
        ),
            List.of(keyboard));
        var hex = RuleDecoder.decode(yaml(
            """
            {type: CC, channels: [0x01], ccs: [0X40],
             value: {min: 0x40, max: 0x7F}, sources: [keyboard]}
            """
        ),
            List.of(keyboard));
        assertEquals(decimal, hex);
        var message = MessageCodec.parse("hex", "B0 40 7F");
        assertTrue(hex.matches(new Envelope(keyboard, 1, message)));
        assertFalse(hex.matches(new Envelope(new Endpoint("bus", Endpoint.Kind.BUS), 1, message)));
    }

    @Test
    void zeroVelocityMatchingPreservesPayload() throws Exception {
        var off = RuleDecoder.decode(yaml("{type: NOTE_OFF}"), List.of(keyboard));
        var on = RuleDecoder.decode(yaml("{type: NOTE_ON}"), List.of(keyboard));
        var e = new Envelope(keyboard, 1, MessageCodec.parse("hex", "90 3C 00"));
        assertTrue(off.matches(e));
        assertFalse(on.matches(e));
        assertEquals("90 3C 00", e.message()
            .hex());
    }

    ApplicationConfig app(String buses, String modules) throws Exception {
        Path application = directory.resolve("application.yaml");
        Files.writeString(application,
            """
            schemaVersion: 0x01
            application:
              backend: java-sound
              networkFile: network.yaml
              ports:
                inputs: {keyboard: Exact Name}
                outputs: {synth: {id: exact-id}}
            """
        );
        Files.writeString(directory.resolve("network.yaml"),
            "schemaVersion: 1\nnetwork:\n  buses: %s\n  modules: %s\n".formatted(buses, modules));
        return ApplicationConfig.read(application);
    }

    NetworkConfig network(ApplicationConfig app) throws Exception {
        return NetworkConfig.read(app, Builtins.providers()
            .stream()
            .collect(Collectors.toMap(p -> p.type(), p -> p)));
    }

    @ParameterizedTest
    @ValueSource(
        strings = { "[keyboard]", "[synth]", "[bus, bus]", "[{name: bus}]", "['']", "[1]" })
    void endpointNamespaceAndBusShape(String buses) throws Exception {
        var app = app(buses, "[]");
        assertThrows(IllegalArgumentException.class, () -> network(app));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            """
            [{name: f, type: midi-message-filter, sources: [synth],
              destinations: [bus], settings: {}}]
            """
            ,
            """
            [{name: f, type: midi-message-filter, sources: [bus],
              destinations: [keyboard], settings: {}}]
            """
            ,
            """
            [{name: f, type: midi-message-filter, sources: [bus, bus],
              destinations: [synth], settings: {}}]
            """
            ,
            """
            [{name: f, type: midi-message-filter, sources: [bus],
              destinations: [bus], settings: {}}]
            """
            ,
            """
            [{name: f, type: midi-message-filter, sources: [unknown],
              destinations: [synth], settings: {}}]
            """
            ,
            """
            [{name: f, type: midi-message-filter, sources: [{name: keyboard}],
              destinations: [synth], settings: {}}]
            """
            ,
            """
            [{name: f, type: midi-monitor, sources: [keyboard],
              destinations: [synth], settings: {}}]
            """
            ,
            "[{name: f, type: midi-emitter, sources: [], destinations: [synth], settings: {}}]" })
    void invalidRoutes(String modules) throws Exception {
        var app = app("[bus]", modules);
        assertThrows(IllegalArgumentException.class, () -> network(app));
    }

    @Test
    void pathsAndTypedBindings() throws Exception {
        var app = app("[]", "[]");
        assertEquals(directory.resolve("network.yaml"), app.network());
        assertEquals(new ApplicationConfig.PortBinding.Name("Exact Name"), app.inputs()
            .get("keyboard"));
        assertEquals(new ApplicationConfig.PortBinding.Id("exact-id"), app.outputs()
            .get("synth"));
        assertEquals(Map.of(), network(app).instances()
            .stream()
            .collect(Collectors.toMap(i -> i.name(), i -> i)));
    }
}
