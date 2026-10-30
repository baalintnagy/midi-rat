package io.midirat.presentation;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

import io.midirat.midi.MessageType;

/** Compact monitor labels; configuration and MIDI message types retain their full names. */
public final class MonitorFormat {
    private static final Map<MessageType, String> LABELS = Map.of(
        MessageType.CONTINUE, "CONT",
        MessageType.TIME_CODE, "TC",
        MessageType.SONG_POSITION, "SONG_POS",
        MessageType.SONG_SELECT, "SONG_SEL",
        MessageType.TUNE_REQUEST, "TUNE_REQ",
        MessageType.ACTIVE_SENSING, "ACT_SEN",
        MessageType.SYSTEM_RESET, "SYS_RES");

    private MonitorFormat() {
    }

    public static String typeLabel(MessageType type) {
        return LABELS.getOrDefault(type, type.name());
    }

    public static MessageType messageType(String label) {
        return Arrays.stream(MessageType.values())
            .filter(type -> typeLabel(type).equals(label)
                || type.name()
                    .equals(label))
            .findFirst()
            .orElseThrow(
                () -> new IllegalArgumentException("Unknown monitor type: %s".formatted(label)));
    }

    public static String timestamp(long nanos) {
        String digits = String.format(Locale.ROOT, "%013d",
            Math.floorMod(nanos, 10_000_000_000_000L));
        return "%s %s %s %s %s".formatted(digits.substring(0, 1), digits.substring(1, 4),
            digits.substring(4, 7), digits.substring(7, 10), digits.substring(10, 13));
    }
}
