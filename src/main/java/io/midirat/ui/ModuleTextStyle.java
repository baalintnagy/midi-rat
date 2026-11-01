package io.midirat.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.tamboui.style.Color;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;

/** Presentation-only token accents; never rewrites module text or MIDI fields. */
final class ModuleTextStyle {
    private static final Pattern TOKENS = Pattern.compile(
        """
        (?<key>[\\p{L}][\\p{L}\\p{N}_ -]*)(?=[:=])\
        |(?<number>\\b(?:0[xX][0-9a-fA-F]+|[0-9]+)\\b)\
        |(?<good>\\b(?:on|true|live|active|open|Ready)\\b)\
        |(?<bad>\\b(?:off|false|PAUSED|disabled|unavailable|failed|error)\\b)\
        |(?<punct>->|[\\[\\]()|,;:=/])\
        """
    );

    private ModuleTextStyle() {
    }

    static Span span(String text, Color color, boolean colors) {
        return colors ? Span.raw(text)
            .fg(color) : Span.raw(text);
    }

    static Line render(String text, boolean colors) {
        if (!colors) {
            return Line.from(text);
        }
        if (text.startsWith("●")
            || text.startsWith("○")) {
            return Line.from(span(text.substring(0, 1),
                text.startsWith("●") ? Color.LIGHT_GREEN : Color.DARK_GRAY, true))
                .append(render(text.substring(1), true));
        }
        if (text.startsWith("CH:")) {
            return MonitorRowStyle.render(text, true);
        }
        if (text.startsWith("Chord:")) {
            Color color = text.equals("Chord: --") ? Color.DARK_GRAY : Color.LIGHT_YELLOW;
            return Line.from(span("Chord:", Color.LIGHT_CYAN, true),
                span(text.substring(6), color, true).bold());
        }
        Matcher matcher = TOKENS.matcher(text);
        List<Span> spans = new ArrayList<>();
        int offset = 0;
        while (matcher.find()) {
            spans.add(Span.raw(text.substring(offset, matcher.start())));
            spans.add(Span.raw(matcher.group())
                .fg(tokenColor(matcher)));
            offset = matcher.end();
        }
        spans.add(Span.raw(text.substring(offset)));
        return Line.from(spans);
    }

    private static Color tokenColor(Matcher token) {
        if (token.group("key") != null) {
            return Color.LIGHT_CYAN;
        }
        if (token.group("number") != null) {
            return Color.LIGHT_YELLOW;
        }
        if (token.group("good") != null) {
            return Color.LIGHT_GREEN;
        }
        if (token.group("bad") != null) {
            return Color.LIGHT_RED;
        }
        return Color.DARK_GRAY;
    }
}
