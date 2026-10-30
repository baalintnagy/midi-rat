package io.midirat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.midirat.api.ModuleInstance;
import io.midirat.config.Value;
import io.midirat.midi.Endpoint;
import io.midirat.midi.Envelope;
import io.midirat.midi.MessageCodec;
import io.midirat.module.Builtins;
import io.midirat.module.Curve;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RemapTest {
    @TempDir
    Path directory;
    private final Endpoint source = new Endpoint("keyboard", Endpoint.Kind.INPUT);

    ModuleInstance prepare(String type, String yaml) throws Exception {
        Path file = directory.resolve("settings.yaml");
        Files.writeString(file, yaml);
        return Builtins.providers()
            .stream()
            .filter(p -> p.type()
                .equals(type))
            .findFirst()
            .orElseThrow()
            .prepare(Value.read(file), List.of(source));
    }

    List<String> process(ModuleInstance module, String hex) {
        return module.process(new Envelope(source, 123, MessageCodec.parse("hex", hex)))
            .stream()
            .map(m -> m.hex())
            .toList();
    }

    @Test
    void orderedFanoutMatchesTransformedBranches() throws Exception {
        var module = prepare("midi-remap",
            """
            rules:
              - match: {type: NOTE, channels: [1]}
                action: {op: channel-map, channels: [2, 3]}
                flow: continue
              - match: {type: NOTE, channels: [2]}
                action:
                  op: note-map
                  entries: [{from: 60, to: {note: 64}}]
                  fallbackTranspose: 12
              - match: {type: NOTE, channels: [3]}
                action: {op: note-map, fallbackTranspose: -0x0C}
            """
        );
        assertEquals(List.of("91 40 64", "92 30 64"), process(module, "90 3C 64"));
        assertEquals(List.of("81 40 00", "82 30 00"), process(module, "80 3C 00"));
    }

    @Test
    void microtonalBendOnlyBeforePositiveAttack() throws Exception {
        var module = prepare("midi-remap",
            """
            rules:
              - match: {type: NOTE}
                action:
                  op: note-map
                  bendRangeSemitones: 2
                  entries: [{from: 61, to: {note: 62, pitchBend: 0x2800}}]
                  fallbackTranspose: 2147483647
            """
        );
        assertEquals(List.of("E3 00 50", "93 3E 64"), process(module, "93 3D 64"));
        assertEquals(List.of("83 3E 00"), process(module, "83 3D 00"));
        assertEquals(List.of("93 3E 00"), process(module, "93 3D 00"));
        assertEquals(List.of("93 7F 64"), process(module, "93 3C 64"));
    }

    @Test
    void reloadDoesNotPairOrReleaseNotes() throws Exception {
        var old = prepare("midi-remap",
            "rules: [{match: {type: NOTE}, action: {op: note-map, fallbackTranspose: 12}}]");
        assertEquals(List.of("90 48 64"), process(old, "90 3C 64"));
        old.close();
        var replacement = prepare("midi-remap",
            "rules: [{match: {type: NOTE}, action: {op: note-map, fallbackTranspose: -12}}]");
        assertEquals(List.of("80 30 00"), process(replacement, "80 3C 00"));
    }

    @Test
    void triggerHysteresisTupleIsolationAndRecreation() throws Exception {
        String yaml =
        """
        rules:
          - match: {type: CC}
            action:
              op: cc-note-trigger
              onThreshold: 64
              offThreshold: 32
              note: 60
              velocity: 100
              offVelocity: 5
              channels: [3, 4]
              passOriginal: true
        """;
        var module = prepare("midi-remap", yaml);
        assertEquals(List.of("B0 16 40", "92 3C 64", "93 3C 64"), process(module, "B0 16 40"));
        assertEquals(List.of("B0 16 7F"), process(module, "B0 16 7F"));
        assertEquals(List.of("B0 16 21"), process(module, "B0 16 21"));
        assertEquals(List.of("B1 16 40", "92 3C 64", "93 3C 64"), process(module, "B1 16 40"));
        assertEquals(List.of("B0 16 20", "82 3C 05", "83 3C 05"), process(module, "B0 16 20"));
        assertEquals(List.of("B0 16 20"), process(module, "B0 16 20"));
        module.close();
        assertEquals(List.of("B1 16 00"), process(prepare("midi-remap", yaml), "B1 16 00"));
    }

    @Test
    void curvesRoundingClampingAndNonmonotonic() {
        var curve = new Curve(List.of(new Curve.Point(0, 0), new Curve.Point(2, 1),
            new Curve.Point(4, 0)));
        assertEquals(0, curve.apply(-1));
        assertEquals(1, curve.apply(1));
        assertEquals(1, curve.apply(3));
        assertEquals(0, curve.apply(8));
    }

    @Test
    void ccBendConversionsAndControllerFanout() throws Exception {
        var module = prepare("midi-remap",
            """
            curves:
              bend:
                points: [{input: 0, output: 0}, {input: 64, output: 8192},
                         {input: 127, output: 16383}]
              cc:
                points: [{input: 0, output: 127}, {input: 8192, output: 64},
                         {input: 16383, output: 0}]
            rules:
              - match: {type: CC}
                action: {op: cc-to-pitch-bend, curve: bend}
              - match: {type: P_BEND}
                action: {op: pitch-bend-to-cc, controllers: [11, 74], curve: cc}
            """
        );
        assertEquals(List.of("E0 00 40"), process(module, "B0 02 40"));
        assertEquals(List.of("B1 0B 40", "B1 4A 40"), process(module, "E1 00 40"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{op: channel-map, channels: []}", "{op: note-map}",
            """
            {op: cc-note-trigger, onThreshold: 32, offThreshold: 64, note: 60,
             velocity: 100, offVelocity: 0, channels: [1], passOriginal: false}
            """
            ,
            "{op: value-curve, field: velocity, curve: missing}",
            "{op: cc-map, controllers: [128]}", "{op: cc-to-pitch-bend, curve: missing}",
            "{op: substitute, format: hex, messages: ['90 3C']}", "{op: drop}" })
    void invalidActions(String action) {
        assertThrows(IllegalArgumentException.class, () -> prepare("midi-remap",
            "rules: [{match: {type: CC}, action: %s}]".formatted(action)));
    }

    @Test
    void blacklistWinsAndWhitelistEmptyPasses() throws Exception {
        var filter = prepare("midi-message-filter",
            "whitelist: [{type: CC}]\nblacklist: [{type: CC, ccs: [64]}]");
        assertTrue(process(filter, "B0 40 7F").isEmpty());
        assertEquals(List.of("B0 41 7F"), process(filter, "B0 41 7F"));
        assertTrue(process(filter, "90 3C 64").isEmpty());
        assertEquals(List.of("90 3C 64"),
            process(prepare("midi-message-filter", "{}"), "90 3C 64"));
    }
}
