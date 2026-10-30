package io.midirat.midi;

import java.util.Arrays;
import java.util.HexFormat;

/** An immutable complete MIDI 1.0 payload, with no display padding. */
public final class MidiMessage {
    public static final int MAX_BYTES = 1_048_576;
    private final byte[] bytes;

    public MidiMessage(byte[] data) {
        if (data.length == 0
            || data.length > MAX_BYTES) {
            throw new IllegalArgumentException("MIDI length must be 1..1048576 bytes");
        }
        bytes = data.clone();
        MessageType type = MessageType.of(status());
        if (type == MessageType.SYSEX) {
            if (bytes.length < 2
                || unsigned(bytes.length - 1) != 0xF7) {
                throw new IllegalArgumentException("SysEx must have F0/F7 framing");
            }
        } else if (type == MessageType.SYSEX_END
            || bytes.length != type.length()) {
            throw new IllegalArgumentException("Incomplete or invalid MIDI message length");
        }
        int end = type == MessageType.SYSEX ? bytes.length - 1 : bytes.length;
        for (int i = 1; i < end; i++) {
            if (unsigned(i) > 127) {
                throw new IllegalArgumentException("Data bytes must be 7-bit; separate realtime");
            }
        }
    }

    public static MidiMessage shortMessage(int status, int... data) {
        byte[] result = new byte[data.length + 1];
        if (status < 128
            || status > 255) {
            throw new IllegalArgumentException("Invalid MIDI status");
        }
        result[0] = (byte) status;
        for (int i = 0; i < data.length; i++) {
            if (data[i] < 0
                || data[i] > 127) {
                throw new IllegalArgumentException("Invalid MIDI data byte");
            }
            result[i + 1] = (byte) data[i];
        }
        return new MidiMessage(result);
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public int length() {
        return bytes.length;
    }

    public int status() {
        return unsigned(0);
    }

    public int data1() {
        return bytes.length > (status() == 0xF0 ? 2 : 1) ? unsigned(1) : 0;
    }

    public int data2() {
        return bytes.length > (status() == 0xF0 ? 3 : 2) ? unsigned(2) : 0;
    }

    public int value14() {
        return data1() | data2() << 7;
    }

    public MessageType type() {
        return MessageType.of(status());
    }

    public MessageType matchType() {
        return type() == MessageType.NOTE_ON
            && data2() == 0 ? MessageType.NOTE_OFF : type();
    }

    public int channel() {
        return type().channel() ? (status() & 15) + 1 : 0;
    }

    public String hex() {
        return HexFormat.ofDelimiter(" ")
            .withUpperCase()
            .formatHex(bytes);
    }

    public MidiMessage withChannel(int channel) {
        if (channel < 1
            || channel > 16
            || !type().channel()) {
            throw new IllegalArgumentException(
                "Channel map requires channel message and channel 1..16");
        }
        byte[] result = bytes();
        result[0] = (byte) ((status() & 0xF0) | (channel - 1));
        return new MidiMessage(result);
    }

    public MidiMessage withData(int index, int value) {
        if (value < 0
            || value > 127
            || index < 1
            || index >= bytes.length) {
            throw new IllegalArgumentException("Invalid MIDI data index/value");
        }
        byte[] result = bytes();
        result[index] = (byte) value;
        return new MidiMessage(result);
    }

    public MidiMessage with14(int value) {
        return shortMessage(status(), value & 127, value >> 7);
    }

    private int unsigned(int index) {
        return Byte.toUnsignedInt(bytes[index]);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MidiMessage message
            && Arrays.equals(bytes, message.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return hex();
    }
}
