package io.midirat.ui;

import java.util.List;
import java.util.Optional;

import io.midirat.api.EmitterControls;

/** Session-local editor state; validation is delegated to the emitter capability. */
final class SequenceEditor {
    private static final int MAX_CHARACTERS = 2_000_000;
    private final StringBuilder text = new StringBuilder();
    private String format = "hex";
    private boolean active;

    boolean active() {
        return active;
    }

    String text() {
        return text.toString();
    }

    String format() {
        return format;
    }

    void begin(EmitterControls emitter) {
        text.setLength(0);
        text.append(String.join(";", emitter.messages()));
        format = emitter.format();
        active = true;
    }

    void accept(int key, Optional<EmitterControls> emitter) {
        switch (key) {
        case 27 -> active = false;
        case 20 -> format = format.equals("hex") ? "message-text" : "hex";
        case 10, 13 -> {
            emitter.ifPresent(controls -> controls.edit(format, entries()));
            active = false;
        }
        case 127, 8 -> {
            if (!text.isEmpty()) {
                text.deleteCharAt(text.length() - 1);
            }
        }
        default -> {
            if (key >= 32
                && key <= 126
                && text.length() < MAX_CHARACTERS) {
                text.append((char) key);
            }
        }
        }
    }

    private List<String> entries() {
        return text.isEmpty() ? List.of()
            : List.of(text.toString()
                .split(";", -1));
    }
}
