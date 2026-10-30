package io.midirat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import io.midirat.backend.MidiBackend.Received;
import io.midirat.backend.UmpCodec;
import io.midirat.backend.WireAssembler;
import io.midirat.midi.MessageCodec;
import io.midirat.midi.MessageType;
import io.midirat.midi.MidiMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MessageTest {
    @ParameterizedTest
    @ValueSource(strings = { "90 3C 64", "90 3C 00", "80 3C 40", "A2 7F 00", "B0 40 7F",
            "C2 10", "D0 40", "E0 00 40", "F1 7F", "F2 7F 7F", "F3 01", "F6", "F8",
            "FA", "FB", "FC", "FE", "FF", "F4", "F5", "F9", "FD", "F0 F7", "F0 7D 01 F7" })
    void textAndUmpRoundTrip(String hex) {
        var message = MessageCodec.parse("hex", hex);
        assertEquals(message, MessageCodec.parse("message-text", MessageCodec.readable(message)));
        var received = new ArrayList<Received>();
        var errors = new ArrayList<String>();
        var assembler = new WireAssembler(received::add, errors::add);
        UmpCodec.encode(5, message)
            .forEach(words -> UmpCodec.decode(5, words, assembler, errors::add));
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(List.of(message), received.stream()
            .map(Received::message)
            .toList());
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "90", "90 3C", "90 3C 80", "C0 01 00", "00", "F0 7D",
            "F0 F8 F7", "F7", "0x90 3C 00", "9 3C 00", "90 3C 00 F8" })
    void malformedHex(String text) {
        assertThrows(IllegalArgumentException.class, () -> MessageCodec.parse("hex", text));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CH:2|NOTE_ON|D1:60|D2:100|Status:144",
            "CH:1|CC|D1:60|D2:100|Status:144",
            "CH:1|PC|D1:60|D2:1|Status:192",
            "CH:1|CLOCK|D1:0|D2:0|Status:248",
            "CH:--|CLOCK|D1:1|D2:0|Status:248",
            "CH:1|NOTE|D1:60|D2:100|Status:144",
            "CH:1|NOTE_ON|D1:60 61|D2:100|Status:144",
            "CH:1|NOTE_ON|D1:60|D2:100|Status:144|123 ns" })
    void contradictoryReadable(String text) {
        assertThrows(IllegalArgumentException.class,
            () -> MessageCodec.parse("message-text", text));
    }

    @Test
    void immutableAndZeroVelocity() {
        var bytes = new byte[] { (byte) 0x90, 60, 0 };
        var message = new MidiMessage(bytes);
        bytes[1] = 1;
        message.bytes()[1] = 2;
        assertEquals(60, message.data1());
        assertEquals(MessageType.NOTE_OFF, message.matchType());
        assertEquals(MessageType.NOTE_ON, message.type());
    }

    @Test
    void wireRealtimeSeparatedAndReceiptPreserved() {
        var received = new ArrayList<Received>();
        var errors = new ArrayList<String>();
        var assembler = new WireAssembler(received::add, errors::add);
        assembler.accept(new byte[] { (byte) 0xF0, 0x7D }, 123);
        assembler.accept(new byte[] { (byte) 0xF8, 1, (byte) 0xF7 }, 456);
        assertEquals("F8", received.get(0)
            .message()
            .hex());
        assertEquals(456, received.get(0)
            .hostReceiptNanos());
        assertEquals("F0 7D 01 F7", received.get(1)
            .message()
            .hex());
        assertEquals(123, received.get(1)
            .hostReceiptNanos());
        assertTrue(errors.isEmpty());
        assembler.accept(new byte[] { (byte) 0xF0 });
        assembler.close();
        assertFalse(errors.isEmpty());
    }

    @Test
    void largeSysexUmpAndUnsupportedMidi2() {
        byte[] bytes = new byte[101];
        bytes[0] = (byte) 0xF0;
        bytes[100] = (byte) 0xF7;
        var message = new MidiMessage(bytes);
        var received = new ArrayList<Received>();
        var errors = new ArrayList<String>();
        var assembler = new WireAssembler(received::add, errors::add);
        UmpCodec.encode(15, message)
            .forEach(w -> UmpCodec.decode(15, w, assembler, errors::add));
        assertEquals(message, received.getFirst()
            .message());
        UmpCodec.decode(15, new int[] { 0x4F000000, 0 }, assembler, errors::add);
        assertEquals(1, errors.size());
        assertEquals(1_500_000_000L, UmpCodec.ticksToNanos(15_000_000, 10_000_000));
    }
}
