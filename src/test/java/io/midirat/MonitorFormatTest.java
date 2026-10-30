package io.midirat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.midirat.midi.MessageType;
import io.midirat.presentation.MonitorFormat;
import org.junit.jupiter.api.Test;

class MonitorFormatTest {
    @Test
    void compactLabelsRetainTypeIdentityForColorsAndFitTheColumn() {
        assertEquals("CONT", MonitorFormat.typeLabel(MessageType.CONTINUE));
        assertEquals("TC", MonitorFormat.typeLabel(MessageType.TIME_CODE));
        assertEquals("SONG_POS", MonitorFormat.typeLabel(MessageType.SONG_POSITION));
        assertEquals("SONG_SEL", MonitorFormat.typeLabel(MessageType.SONG_SELECT));
        assertEquals("TUNE_REQ", MonitorFormat.typeLabel(MessageType.TUNE_REQUEST));
        assertEquals("ACT_SEN", MonitorFormat.typeLabel(MessageType.ACTIVE_SENSING));
        assertEquals("SYS_RES", MonitorFormat.typeLabel(MessageType.SYSTEM_RESET));
        for (var type : MessageType.values()) {
            String label = MonitorFormat.typeLabel(type);
            assertTrue(label.length() <= 11);
            assertEquals(type, MonitorFormat.messageType(label));
            assertEquals(type, MonitorFormat.messageType(type.name()));
        }
    }

    @Test
    void timestampShowsExactlyThirteenTrailingDigitsAcrossRollover() {
        assertEquals("0 000 000 000 009", MonitorFormat.timestamp(9));
        assertEquals("1 234 567 890 123", MonitorFormat.timestamp(91_234_567_890_123L));
        assertEquals("9 999 999 999 999", MonitorFormat.timestamp(9_999_999_999_999L));
        assertEquals("0 000 000 000 000", MonitorFormat.timestamp(10_000_000_000_000L));
        assertEquals("9 999 999 999 999", MonitorFormat.timestamp(-1));
    }
}
