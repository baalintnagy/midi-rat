package io.midirat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import io.midirat.chord.TonalChordRecognizer;
import io.midirat.midi.Endpoint;
import io.midirat.midi.Envelope;
import io.midirat.midi.MidiMessage;
import io.midirat.module.ChordModule;
import org.junit.jupiter.api.Test;

class ChordTest {
    @Test
    void majorSeventhsWithoutFifthsWorkAcrossKeysAndInversions() {
        var recognizer = new TonalChordRecognizer();
        assertEquals("Cmaj7(no5)", recognizer.recognize(List.of(60, 64, 71))
            .getFirst());
        for (int transpose = 0; transpose < 12; transpose++) {
            for (int inversion = 0; inversion < 3; inversion++) {
                var intervals = List.of(0, 4, 11);
                int rotation = inversion;
                int offset = transpose;
                var pitches = IntStream.range(0, 3)
                    .map(index -> 48 + offset + intervals.get((index + rotation) % 3)
                        + (index + rotation >= 3 ? 12 : 0))
                    .boxed()
                    .toList();
                var candidates = recognizer.recognize(pitches);
                assertTrue(candidates.stream()
                    .anyMatch(label -> label.contains("maj7(no5)")), candidates.toString());
            }
        }
        assertFalse(recognizer.recognize(List.of(60, 64, 67, 71))
            .contains("Cmaj7(no5)"));
    }

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
    void minorSeventhsWithoutFifthsWorkAcrossKeysAndInversions() {
        var recognizer = new TonalChordRecognizer();
        assertEquals("Cm7(no5)", recognizer.recognize(List.of(60, 63, 70))
            .getFirst());
        for (int transpose = 0; transpose < 12; transpose++) {
            for (int inversion = 0; inversion < 3; inversion++) {
                var intervals = List.of(0, 3, 10);
                int rotation = inversion;
                int offset = transpose;
                var pitches = IntStream.range(0, 3)
                    .map(index -> 48 + offset + intervals.get((index + rotation) % 3)
                        + (index + rotation >= 3 ? 12 : 0))
                    .boxed()
                    .toList();
                var candidates = recognizer.recognize(pitches);
                assertTrue(candidates.stream()
                    .anyMatch(label -> label.contains("m7(no5)")), candidates.toString());
            }
        }
        assertFalse(recognizer.recognize(List.of(60, 63, 67, 70))
            .contains("Cm7(no5)"));
    }

    @Test
    void fourthLabelsDescribeMinorQualityAndMissingFifthsExplicitly() {
        var recognizer = new TonalChordRecognizer();
        assertTrue(recognizer.recognize(List.of(55, 58, 60, 62))
            .contains("Gmadd4"));
        assertTrue(recognizer.recognize(List.of(55, 58, 60, 62, 65))
            .contains("Gm7add4"));
        var quartal = recognizer.recognize(List.of(55, 60, 65, 70));
        assertTrue(quartal.contains("Gm7add4(no5)"));
        assertFalse(quartal.contains("Gm7add4"));
        for (int transpose = 0; transpose < 12; transpose++) {
            int offset = transpose;
            var pitches = List.of(55, 60, 65, 70)
                .stream()
                .map(pitch -> pitch + offset)
                .toList();
            var labels = recognizer.recognize(pitches);
            assertTrue(labels.stream()
                .anyMatch(label -> label.contains("m7add4(no5)")));
            assertFalse(labels.stream()
                .anyMatch(label -> label.matches("[A-G][#b]*4(/.*)?")));
        }
        assertTrue(recognizer.recognize(List.of(53, 55, 60, 65, 70))
            .contains("Gm7add4(no5)/F"));
    }

    @Test
    void addedFourthWorksAcrossKeysInversionsAndDoublings() {
        var recognizer = new TonalChordRecognizer();
        var example = recognizer.recognize(List.of(48, 55, 60, 64, 65));
        assertEquals("Cadd4", example.getFirst());
        assertTrue(example.contains("Emb6b9/C"));
        for (int transpose = 0; transpose < 12; transpose++) {
            for (int inversion = 0; inversion < 4; inversion++) {
                var intervals = List.of(0, 4, 5, 7);
                int rotation = inversion;
                int offset = transpose;
                var pitches = IntStream.range(0, 4)
                    .map(index -> 48 + offset + intervals.get((index + rotation) % 4)
                        + (index + rotation >= 4 ? 12 : 0))
                    .boxed()
                    .toList();
                var candidates = recognizer.recognize(pitches);
                assertTrue(candidates.stream()
                    .anyMatch(label -> label.contains("add4")), candidates.toString());
            }
        }
    }

    @Test
    void recognizesUpperChordsOverANonChordBassIncludingBassDoublings() {
        var recognizer = new TonalChordRecognizer();
        for (var pitches : List.of(List.of(53, 64, 67, 72),
            List.of(53, 64, 67, 72, 77), List.of(77, 72, 67, 64, 53))) {
            var candidates = recognizer.recognize(pitches);
            assertEquals("C/F", candidates.getFirst());
            assertTrue(candidates.contains("Emb6b9/F"));
            assertEquals(candidates.size(), candidates.stream()
                .distinct()
                .count());
        }
        assertEquals("Cmaj7", recognizer.recognize(List.of(60, 64, 67, 71))
            .getFirst());
        assertTrue(recognizer.recognize(List.of(54, 61, 65, 68))
            .contains("Db/Gb"));
        assertTrue(recognizer.recognize(List.of(54, 61, 65, 68))
            .contains("C#/F#"));
        assertEquals(List.of(), recognizer.recognize(List.of(60, 61, 62)));
    }

    @Test
    void simplerInterpretationsComeFirstAndMajorSeventhsStayDistinct() {
        var recognizer = new TonalChordRecognizer();
        var candidates = recognizer.recognize(List.of(57, 60, 65, 67));
        assertTrue(candidates.contains("Fadd9/A"));
        assertTrue(candidates.contains("Am7#5"));
        assertTrue(candidates.indexOf("Fadd9/A") < candidates.indexOf("Am7#5"));
        var inverted = recognizer.recognize(List.of(53, 58, 60, 62));
        assertTrue(inverted.contains("Bbadd9/F"));
        assertTrue(inverted.contains("Dm7#5/F"));
        assertTrue(inverted.indexOf("Bbadd9/F") < inverted.indexOf("Dm7#5/F"));
        assertTrue(recognizer.recognize(List.of(60, 64, 67, 71))
            .contains("Cmaj7"));
        assertTrue(recognizer.recognize(List.of(60, 64, 67, 70))
            .contains("C7"));
    }

    @Test
    void enharmonicChordLabelsPreferFewerAccidentalsAndKeepBothSpellings() {
        var recognizer = new TonalChordRecognizer();
        var major = recognizer.recognize(List.of(61, 65, 68));
        assertEquals("Db", major.getFirst());
        assertTrue(major.contains("C#"));
        var minor = recognizer.recognize(List.of(61, 64, 68));
        assertEquals("C#m", minor.getFirst());
        assertTrue(minor.contains("Dbm"));
        var inversion = recognizer.recognize(List.of(65, 68, 73));
        assertTrue(inversion.contains("Db/F"));
        assertTrue(inversion.contains("C#/F"));
        assertTrue(inversion.indexOf("Db/F") < inversion.indexOf("C#/F"));
        var natural = recognizer.recognize(List.of(60, 64, 67));
        assertEquals("C", natural.getFirst());
        assertEquals(natural.size(), natural.stream()
            .distinct()
            .count());
        var doubleSharp = recognizer.recognize(List.of(63, 67, 70));
        assertEquals("Eb", doubleSharp.getFirst());
        assertTrue(doubleSharp.contains("D#"));
    }

    @Test
    void retriggersThenUnrecognizedClusterDoNotLeaveThePreviousChordHeld() {
        var module = new ChordModule(new TonalChordRecognizer());
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
    void bundledTonalRecognizesTriadsSeventhsInversionsAndLargerChords() {
        var recognizer = new TonalChordRecognizer();
        assertTrue(recognizer.recognize(List.of(60, 64, 67))
            .contains("C"));
        assertTrue(recognizer.recognize(List.of(60, 63, 67))
            .contains("Cm"));
        assertTrue(recognizer.recognize(List.of(62, 66, 69, 72))
            .contains("D7"));
        assertTrue(recognizer.recognize(List.of(54, 57, 60, 62))
            .contains("D7/F#"));
        assertFalse(recognizer.recognize(List.of(60, 64, 67, 71, 74, 81))
            .isEmpty());
        assertEquals(List.of(), recognizer.recognize(List.of()));
        assertEquals(List.of(), recognizer.recognize(List.of(60, 61, 62)));
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
    void displaysLibraryCandidatesAndUnrecognizedNotesWithoutEmittingMidi() {
        var module = new ChordModule(new TonalChordRecognizer());
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
