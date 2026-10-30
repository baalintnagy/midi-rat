package io.midirat.backend;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import io.midirat.midi.MessageType;
import io.midirat.midi.MidiMessage;

/** MIDI 1.0 channel/system UMP and SysEx7. Native MIDI 2.0 is reported, never narrowed. */
public final class UmpCodec {
    private UmpCodec() {
    }

    public static List<int[]> encode(int group, MidiMessage message) {
        if (message.type() != MessageType.SYSEX) {
            int type = message.channel() == 0 ? 1 : 2;
            return List.of(new int[] { type << 28 | group << 24 | message.status() << 16
                | message.data1() << 8 | message.data2() });
        }
        byte[] bytes = message.bytes();
        List<int[]> packets = new ArrayList<>();
        int end = bytes.length - 1;
        int offset = 1;
        do {
            int count = Math.min(6, end - offset);
            boolean first = offset == 1;
            boolean last = offset + count == end;
            int status = first
                && last ? 0 : first ? 1 : last ? 3 : 2;
            int[] words = { 3 << 28 | group << 24 | status << 20 | count << 16, 0 };
            for (int i = 0; i < count; i++) {
                int index = i + 2;
                words[index / 4] |= Byte.toUnsignedInt(bytes[offset + i]) << (24 - index % 4 * 8);
            }
            packets.add(words);
            offset += count;
        } while (offset < end);
        return List.copyOf(packets);
    }

    public static void decode(int group, int[] words, WireAssembler assembler,
        Consumer<String> errors) {
        decode(group, words, assembler, errors, System.nanoTime());
    }

    public static void decode(int group, int[] words, WireAssembler assembler,
        Consumer<String> errors, long receipt) {
        if (words.length == 0
            || (words[0] >>> 24 & 15) != group) {
            return;
        }
        int type = words[0] >>> 28;
        if (type == 1
            || type == 2) {
            int status = words[0] >>> 16 & 255;
            int length = MessageType.of(status)
                .length();
            if (length < 1
                || (type == 1) != (status >= 0xF0)) {
                errors.accept("Malformed MIDI 1.0 UMP");
                return;
            }
            byte[] bytes = new byte[length];
            for (int i = 0; i < length; i++) {
                bytes[i] = (byte) (words[0] >>> (16 - 8 * i));
            }
            assembler.accept(bytes, receipt);
        } else if (type == 3
            && words.length == 2) {
            int count = words[0] >>> 16 & 15;
            int status = words[0] >>> 20 & 15;
            if (count > 6
                || status > 3) {
                errors.accept("Malformed SysEx7 UMP");
                return;
            }
            boolean first = status == 0
                || status == 1;
            boolean last = status == 0
                || status == 3;
            byte[] bytes = new byte[count + (first ? 1 : 0) + (last ? 1 : 0)];
            int cursor = 0;
            if (first) {
                bytes[cursor++] = (byte) 0xF0;
            }
            for (int i = 0; i < count; i++) {
                int index = i + 2;
                bytes[cursor++] = (byte) (words[index / 4] >>> (24 - index % 4 * 8));
            }
            if (last) {
                bytes[cursor] = (byte) 0xF7;
            }
            assembler.accept(bytes, receipt);
        } else if (type != 0) {
            errors.accept("Unsupported native UMP type %d (MIDI 2.0 deferred)".formatted(type));
        }
    }

    public static long ticksToNanos(long ticks, long frequency) {
        if (frequency <= 0
            || ticks < 0) {
            throw new IllegalArgumentException("Invalid native clock");
        }
        return Math.addExact(Math.multiplyExact(ticks / frequency, 1_000_000_000L),
            BigInteger.valueOf(ticks % frequency)
                .multiply(BigInteger.valueOf(1_000_000_000L))
                .divide(BigInteger.valueOf(frequency))
                .longValueExact());
    }
}
