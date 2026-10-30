package io.midirat.midi;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

public final class MessageCodec {
    private MessageCodec() {
    }

    public static int integer(String text) {
        if (!text.matches("-?(?:0[xX][0-9a-fA-F]+|[0-9]+)")) {
            throw new IllegalArgumentException("Invalid integer: %s".formatted(text));
        }
        boolean negative = text.startsWith("-");
        String digits = negative ? text.substring(1) : text;
        BigInteger value = digits.startsWith("0x")
            || digits.startsWith("0X")
                ? new BigInteger(digits.substring(2), 16)
                : new BigInteger(digits, 10);
        return (negative ? value.negate() : value).intValueExact();
    }

    public static MidiMessage parse(String format, String text) {
        if (format.equals("hex")) {
            return hex(text);
        }
        if (!format.equals("message-text")) {
            throw new IllegalArgumentException("format must be hex or message-text");
        }
        String clean = text.replaceFirst("\\s*\\[[^\\]]*]\\s*$", "")
            .trim();
        if (clean.startsWith("SYSEX")) {
            String[] fields = clean.split("\\|", -1);
            if (fields.length != 2
                || !fields[0].trim()
                    .equals("SYSEX")
                || !fields[1].trim()
                    .startsWith("Bytes:")) {
                throw new IllegalArgumentException("Expected SYSEX | Bytes: <hex>");
            }
            MidiMessage message = hex(fields[1].trim()
                .substring(6)
                .trim());
            if (message.type() != MessageType.SYSEX) {
                throw new IllegalArgumentException("Expected complete SysEx");
            }
            return message;
        }
        String[] fields = clean.split("\\|", -1);
        if (fields.length != 5) {
            throw new IllegalArgumentException("Expected CH | TYPE | D1 | D2 | Status");
        }
        String channel = field(fields[0], "CH");
        MessageType type = MessageType.valueOf(fields[1].trim());
        int d1 = integer(field(fields[2], "D1"));
        int d2 = integer(field(fields[3], "D2"));
        int status = integer(field(fields[4], "Status"));
        if (type == MessageType.NOTE
            || type == MessageType.SYSEX
            || type == MessageType.SYSEX_START
            || type == MessageType.SYSEX_END
            || status < 128
            || status > 255
            || MessageType.of(status) != type) {
            throw new IllegalArgumentException("Type/status contradiction");
        }
        int ch = channel.equals("--") ? 0 : integer(channel);
        if (ch != (type.channel() ? (status & 15) + 1 : 0)
            || (!type.channel()
                && !channel.equals("--"))) {
            throw new IllegalArgumentException("Channel/status contradiction");
        }
        if ((type.length() < 3
            && d2 != 0)
            || (type.length() < 2
                && d1 != 0)) {
            throw new IllegalArgumentException("Absent data bytes must display zero");
        }
        return switch (type.length()) {
        case 3 -> MidiMessage.shortMessage(status, d1, d2);
        case 2 -> MidiMessage.shortMessage(status, d1);
        default -> MidiMessage.shortMessage(status);
        };
    }

    public static List<MidiMessage> sequence(String format, List<String> entries) {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("Cannot send an empty sequence");
        }
        return entries.stream()
            .map(text -> parse(format, text))
            .toList();
    }

    public static String readable(MidiMessage message) {
        if (message.type() == MessageType.SYSEX) {
            return "SYSEX | Bytes: %s".formatted(message.hex());
        }
        return "CH:%s | %s | D1:%d | D2:%d | Status:%d".formatted(
            message.channel() == 0 ? "--" : Integer.toString(message.channel()),
            message.type(), message.data1(), message.data2(), message.status());
    }

    public static String decoded(MidiMessage m) {
        return switch (m.type()) {
        case NOTE_ON, NOTE_OFF -> "note=%d velocity=%d".formatted(m.data1(), m.data2());
        case CC -> "controller=%d value=%d".formatted(m.data1(), m.data2());
        case POLY_AT -> "note=%d pressure=%d".formatted(m.data1(), m.data2());
        case CH_PR -> "pressure=%d".formatted(m.data1());
        case PC -> "program=%d (wire numbering)".formatted(m.data1());
        case P_BEND -> "bend=%d center=8192".formatted(m.value14());
        case SONG_POSITION -> "position=%d MIDI beats".formatted(m.value14());
        case SONG_SELECT -> "song=%d".formatted(m.data1());
        case TIME_CODE -> "quarter-frame part=%d nibble=%d".formatted(
            m.data1() >> 4, m.data1() & 15);
        case SYSEX -> "opaque SysEx length=%d".formatted(m.length());
        default -> m.type()
            .name();
        };
    }

    private static String field(String text, String label) {
        String[] parts = text.trim()
            .split(":", -1);
        if (parts.length != 2
            || !parts[0].equals(label)) {
            throw new IllegalArgumentException("Expected %s field".formatted(label));
        }
        return parts[1].trim();
    }

    private static MidiMessage hex(String text) {
        String[] tokens = text.trim()
            .split("\\s+");
        if (Arrays.stream(tokens)
            .anyMatch(token -> !token.matches("[0-9A-Fa-f]{2}"))) {
            throw new IllegalArgumentException("Hex bytes need exactly two digits without 0x");
        }
        byte[] bytes = new byte[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            bytes[i] = (byte) Integer.parseInt(tokens[i], 16);
        }
        return new MidiMessage(bytes);
    }
}
