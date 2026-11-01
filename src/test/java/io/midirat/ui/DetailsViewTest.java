package io.midirat.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import io.midirat.midi.Endpoint;
import io.midirat.midi.Envelope;
import io.midirat.midi.MessageCodec;
import io.midirat.module.FilterModule;
import io.midirat.module.MonitorModule;
import org.junit.jupiter.api.Test;

class DetailsViewTest {
    @Test
    void payloadExpansionStaysInTheDetailView() {
        var monitor = new MonitorModule(20, true);
        var input = new Endpoint("keys", Endpoint.Kind.INPUT);
        monitor.process(new Envelope(input, 123, MessageCodec.parse("hex", "90 3D 64")));
        monitor.toggleDetails();
        assertTrue(monitor.details()
            .content()
            .lines(5)
            .stream()
            .anyMatch(line -> line.contains("length=3")));
        assertFalse(monitor.widget()
            .lines(5)
            .stream()
            .anyMatch(line -> line.contains("length=3")));
    }

    @Test
    void filterHasFullScreenRulesAndMonitorLogWithHorizontalPanning() {
        var filter = new FilterModule(List.of(), List.of(),
            List.of("whitelist: []", "blacklist: []"));
        var input = new Endpoint("keys", Endpoint.Kind.INPUT);
        filter.process(new Envelope(input, 123, MessageCodec.parse("hex", "90 3D 64")));
        var state = new DetailsView.State();
        var area = Rect.of(100, 24);
        var first = Buffer.empty(area);
        new DetailsView("M-IN", "midi-message-filter", filter.details(), state, true)
            .render(area, first);
        String rendered = text(first);
        assertTrue(rendered.contains("Rules | m page"));
        assertTrue(rendered.contains("whitelist: []"));
        assertTrue(rendered.contains("blacklist: []"));
        assertFalse(rendered.contains("Passed LOG"));
        assertFalse(rendered.contains("NOTE_ON"));
        assertFalse(rendered.contains("CONNECTIONS / ACTIVITY"));
        assertTrue(rendered.chars()
            .filter(symbol -> symbol == '╭')
            .count() == 1);
        filter.nextMode();
        var passed = Buffer.empty(area);
        new DetailsView("M-IN", "midi-message-filter", filter.details(), state, true)
            .render(area, passed);
        assertTrue(text(passed).contains("Passed LOG"));
        assertTrue(text(passed).contains("NOTE_ON"));
        assertFalse(text(passed).contains("whitelist: []"));
        assertTrue(text(passed).chars()
            .filter(symbol -> symbol == '╭')
            .count() == 1);
        state.horizontal.move(1000);
        var panned = Buffer.empty(area);
        new DetailsView("M-IN", "midi-message-filter", filter.details(), state, true)
            .render(area, panned);
        assertTrue(text(panned).contains("Note: (C#,Db)"));
        filter.nextMode();
        var blocked = Buffer.empty(area);
        new DetailsView("M-IN", "midi-message-filter", filter.details(), state, true)
            .render(area, blocked);
        assertTrue(text(blocked).contains("Blocked LOG"));
        assertFalse(text(blocked).contains("NOTE_ON"));
        filter.nextMode();
        var rules = Buffer.empty(area);
        new DetailsView("M-IN", "midi-message-filter", filter.details(), state, true)
            .render(area, rules);
        assertTrue(text(rules).contains("Rules | m page"));
    }

    @Test
    void rulesPageUsesTheSharedPanelAndScrollsToTheLastRule() {
        var filter = new FilterModule(List.of(), List.of(), IntStream.range(0, 30)
            .mapToObj(index -> "rule-%02d".formatted(index))
            .toList());
        var area = Rect.of(80, 12);
        var state = new DetailsView.State();
        var first = Buffer.empty(area);
        new DetailsView("filter", "midi-message-filter", filter.details(), state, true)
            .render(area, first);
        assertTrue(text(first).contains("rule-00"));
        assertFalse(text(first).contains("rule-29"));
        state.information.move(100);
        var last = Buffer.empty(area);
        new DetailsView("filter", "midi-message-filter", filter.details(), state, true)
            .render(area, last);
        assertTrue(text(last).contains("rule-29"));
        assertFalse(text(last).contains("rule-00"));
    }

    @Test
    void tinyAndPlainDetailViewsRemainRenderable() {
        var filter = new FilterModule(List.of(), List.of());
        for (var area : List.of(Rect.of(1, 1), Rect.of(20, 5), Rect.of(80, 18))) {
            var buffer = Buffer.empty(area);
            new DetailsView("filter", "midi-message-filter", filter.details(),
                new DetailsView.State(), false).render(area, buffer);
            assertTrue(area.positions()
                .allMatch(position -> buffer.get(position)
                    .style()
                    .fg()
                    .isEmpty()));
        }
    }

    private static String text(Buffer buffer) {
        return buffer.area()
            .positions()
            .map(position -> buffer.get(position)
                .symbol())
            .collect(Collectors.joining());
    }
}
