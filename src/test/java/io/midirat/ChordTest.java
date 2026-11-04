package io.midirat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.midirat.chord.JavaChordRecognizer;
import io.midirat.midi.Endpoint;
import io.midirat.midi.Envelope;
import io.midirat.midi.MidiMessage;
import io.midirat.module.ChordModule;
import org.junit.jupiter.api.Test;

class ChordTest {
    @Test
    void limitsRankedCandidatesAndShowsConfigurationOnlyInDetails() {
        var module = new ChordModule(_ -> List.of("first", "second", "third"), 2);
        var input = new Endpoint("input", Endpoint.Kind.INPUT);
        send(module, input, input, 0x90, 60, 100);
        assertEquals(List.of("Chord: first | second", "Notes: C"), module.widgetLines());
        assertEquals(List.of("maxChords: 2"), module.details()
            .information());
        var unlimited = new ChordModule(_ -> List.of("first", "second", "third"));
        send(unlimited, input, input, 0x90, 60, 100);
        assertEquals("Chord: first | second | third", unlimited.widgetLines()
            .getFirst());
    }

    @Test
    void retriggersThenUnrecognizedClusterDoNotLeaveThePreviousChordHeld() {
        var module = new ChordModule(new JavaChordRecognizer());
        var bus = new Endpoint("sostenuto-output", Endpoint.Kind.BUS);
        for (int note : List.of(60, 64, 67)) {
            send(module, bus, bus, 0x90, note, 100);
            send(module, bus, bus, 0x90, note, 100);
        }
        assertTrue(module.widgetLines()
            .getFirst()
            .contains("C"));
        for (int note : List.of(61, 62, 63, 65, 66, 68, 69, 70, 71)) {
            send(module, bus, bus, 0x90, note, 100);
        }
        assertEquals("Chord: unrecognized", module.widgetLines()
            .getFirst());
        for (int note = 60; note <= 71; note++) {
            send(module, bus, bus, 0x80, note, 0);
        }
        assertEquals(List.of("Chord: --", "Notes: --"),
            module.widgetLines());
        for (int note : List.of(62, 65, 69)) {
            send(module, bus, bus, 0x90, note, 100);
        }
        assertTrue(module.widgetLines()
            .getFirst()
            .contains("Dm"));
    }

    @Test
    void mergesEndpointsAndChannelsAndTreatsRepeatedNoteOnsAsRetriggers() {
        var received = new AtomicReference<List<Integer>>(List.of());
        var module = new ChordModule(pitches -> {
            received.set(pitches);
            return List.of("example");
        });
        var input = new Endpoint("keyboard", Endpoint.Kind.INPUT);
        var bus = new Endpoint("bus", Endpoint.Kind.BUS);
        send(module, input, input, 0x90, 60, 90);
        send(module, bus, bus, 0x90, 60, 100);
        send(module, bus, input, 0x91, 64, 100);
        send(module, input, bus, 0x92, 67, 100);
        assertEquals(List.of(60, 64, 67), received.get());
        send(module, bus, input, 0x80, 60, 0);
        assertEquals(List.of(64, 67), received.get());
        send(module, input, bus, 0x90, 60, 0);
        assertEquals(List.of(64, 67), received.get());
        send(module, input, input, 0x80, 64, 0);
        assertEquals(List.of(64, 67), received.get());
        send(module, bus, bus, 0x81, 64, 0);
        send(module, bus, bus, 0x82, 67, 0);
        assertEquals(List.of(), received.get());
        assertEquals("Chord: --", module.widgetLines()
            .getFirst());
    }

    @Test
    void displaysCandidatesAndUnrecognizedNotesWithoutEmittingMidi() {
        var module = new ChordModule(new JavaChordRecognizer());
        var source = new Endpoint("input", Endpoint.Kind.INPUT);
        assertEquals("Chord: --", module.widgetLines()
            .getFirst());
        send(module, source, source, 0x90, 60, 100);
        assertEquals("Chord: unrecognized", module.widgetLines()
            .getFirst());
        send(module, source, source, 0x90, 64, 100);
        send(module, source, source, 0x90, 67, 100);
        assertTrue(module.widgetLines()
            .getFirst()
            .contains("C"));
        assertEquals(2, module.widgetLines()
            .size());
        assertEquals("Notes: C, E, G", module.widgetLines()
            .getLast());
        send(module, source, source, 0xB0, 64, 127);
        send(module, source, source, 0x80, 60, 0);
        assertEquals("Notes: E, G", module.widgetLines()
            .getLast());
    }

    @Test
    void explicitChannelReleaseMessagesClearOnlyThatChannelsHeldKeys() {
        var module = new ChordModule(_ -> List.of("example"));
        var source = new Endpoint("input", Endpoint.Kind.INPUT);
        send(module, source, source, 0x90, 60, 100);
        send(module, source, source, 0x91, 64, 100);
        send(module, source, source, 0xB0, 123, 0);
        assertEquals("Notes: E", module.widgetLines()
            .getLast());
        send(module, source, source, 0xB1, 120, 0);
        assertEquals("Chord: --", module.widgetLines()
            .getFirst());
    }

    @Test
    void releasingAPitchOnOneChannelKeepsItHeldOnAnotherChannel() {
        var module = new ChordModule(_ -> List.of());
        var source = new Endpoint("input", Endpoint.Kind.INPUT);
        send(module, source, source, 0x90, 60, 100);
        send(module, source, source, 0x91, 60, 100);
        send(module, source, source, 0x80, 60, 0);
        assertEquals("Notes: C", module.widgetLines()
            .getLast());
        send(module, source, source, 0x91, 60, 0);
        assertEquals("Notes: --", module.widgetLines()
            .getLast());
    }

    @Test
    void noteNamesIncludeBothEnharmonicsAndFollowMidiOrderAcrossOctaves() {
        var module = new ChordModule(_ -> List.of());
        var source = new Endpoint("input", Endpoint.Kind.INPUT);
        for (int note : List.of(127, 72, 61, 0)) {
            send(module, source, source, 0x90, note, 100);
        }
        assertEquals(List.of("Chord: unrecognized", "Notes: C, (C#,Db), C, G"),
            module.widgetLines());
    }

    private static void send(ChordModule module, Endpoint source, Endpoint origin,
        int status, int data1, int data2) {
        assertEquals(List.of(), module.process(new Envelope(source, 1,
            MidiMessage.shortMessage(status, data1, data2), origin)));
    }
}
