package io.midirat.midi;

public enum MessageType {
    NOTE, NOTE_ON, NOTE_OFF, CC, P_BEND, CH_PR, POLY_AT, PC,
    TIME_CODE, SONG_POSITION, SONG_SELECT, TUNE_REQUEST, CLOCK,
    START, CONTINUE, STOP, ACTIVE_SENSING, SYSTEM_RESET, SYSEX,
    SYSEX_START, SYSEX_END, OTHER;

    public boolean channel() {
        return ordinal() <= PC.ordinal();
    }

    public int length() {
        return switch (this) {
        case NOTE, NOTE_ON, NOTE_OFF, CC, P_BEND, POLY_AT, SONG_POSITION -> 3;
        case CH_PR, PC, TIME_CODE, SONG_SELECT -> 2;
        case SYSEX -> -1;
        default -> 1;
        };
    }

    public static MessageType of(int status) {
        if (status < 0xF0) {
            return switch (status & 0xF0) {
            case 0x80 -> NOTE_OFF;
            case 0x90 -> NOTE_ON;
            case 0xA0 -> POLY_AT;
            case 0xB0 -> CC;
            case 0xC0 -> PC;
            case 0xD0 -> CH_PR;
            case 0xE0 -> P_BEND;
            default -> throw new IllegalArgumentException("Missing MIDI status");
            };
        }
        return switch (status) {
        case 0xF0 -> SYSEX;
        case 0xF1 -> TIME_CODE;
        case 0xF2 -> SONG_POSITION;
        case 0xF3 -> SONG_SELECT;
        case 0xF6 -> TUNE_REQUEST;
        case 0xF7 -> SYSEX_END;
        case 0xF8 -> CLOCK;
        case 0xFA -> START;
        case 0xFB -> CONTINUE;
        case 0xFC -> STOP;
        case 0xFE -> ACTIVE_SENSING;
        case 0xFF -> SYSTEM_RESET;
        default -> OTHER;
        };
    }
}
