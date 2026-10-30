package io.midirat.ui;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.style.AnsiColor;
import dev.tamboui.style.Color;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import io.midirat.midi.MessageType;
import io.midirat.presentation.MonitorFormat;

/** ANSI category colors and numeric accents shared by full and compact monitor views. */
public final class MonitorRowStyle {
    private MonitorRowStyle() {
    }

    public static Line render(String row, boolean colors) {
        if (!colors
            || !row.startsWith("CH:")) {
            return Line.from(row);
        }
        String[] fields = row.split(" \\| ", -1);
        if (fields.length != 7
            && fields.length != 8
            && fields.length != 9) {
            return Line.from(row);
        }
        MessageType type;
        try {
            type = MonitorFormat.messageType(fields[1].trim());
        } catch (IllegalArgumentException _) {
            return Line.from(row);
        }
        boolean dark = type == MessageType.NOTE_OFF
            || type == MessageType.P_BEND
            || type == MessageType.CH_PR;
        List<Color> palette = new ArrayList<>(List.of(
            Color.ansi(AnsiColor.WHITE),
            category(type),
            Color.ansi(dark ? AnsiColor.YELLOW : AnsiColor.BRIGHT_YELLOW),
            Color.ansi(dark ? AnsiColor.GREEN : AnsiColor.BRIGHT_GREEN),
            Color.ansi(dark ? AnsiColor.RED : AnsiColor.BRIGHT_RED),
            Color.ansi(AnsiColor.BRIGHT_BLUE),
            Color.ansi(AnsiColor.BRIGHT_BLACK)));
        if (fields.length == 9) {
            palette.add(6, Color.ansi(AnsiColor.BRIGHT_BLUE));
        }
        if (fields.length >= 8) {
            palette.add(category(type));
        }
        List<Span> spans = new ArrayList<>();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                spans.add(Span.raw(" | "));
            }
            int labelEnd = i == 0
                || i == 2
                || i == 3
                || i == 4
                || fields.length >= 8
                    && i == fields.length - 1
                        ? fields[i].indexOf(':') + 1
                        : 0;
            if (labelEnd > 0) {
                spans.add(Span.raw(fields[i].substring(0, labelEnd)));
            }
            spans.add(Span.raw(fields[i].substring(labelEnd))
                .fg(palette.get(i)));
        }
        return Line.from(spans);
    }

    private static Color category(MessageType type) {
        return Color.ansi(switch (type) {
        case NOTE_ON -> AnsiColor.BRIGHT_CYAN;
        case NOTE_OFF -> AnsiColor.CYAN;
        case CC -> AnsiColor.BRIGHT_YELLOW;
        case P_BEND -> AnsiColor.YELLOW;
        case CH_PR, POLY_AT, PC -> AnsiColor.BRIGHT_MAGENTA;
        case OTHER -> AnsiColor.BRIGHT_RED;
        default -> AnsiColor.BRIGHT_BLACK;
        });
    }
}
