package io.midirat.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import io.midirat.host.HostStatus;
import org.junit.jupiter.api.Test;

class HostDetailsViewTest {
    @Test
    void hostDetailsPageThroughRuntimeConfigurationAndActivityWithScrolling() {
        var status = new HostStatus(new HostStatus.Devices("winmm", 8, 1, "jitter=4300 ns",
            List.of(new HostStatus.Connection("postSSB", "Physical output", true, 0, 0))),
            List.of(), IntStream.range(0, 30)
                .mapToObj(
                    index -> new HostStatus.Route("processor-%02d".formatted(index), true, 0, 0))
                .toList());
        var state = new HostDetailsView.State();
        var area = Rect.of(100, 24);
        var first = Buffer.empty(area);
        var view = new HostDetailsView(status,
            List.of("Application file: C:/config/application.yaml", "Network YAML (active):",
                "destinations: [postSSB]"),
            List.of("latest event"), 2, state, true);
        view.render(area, first);
        assertTrue(text(first).contains("STATUS"));
        assertFalse(text(first).contains("processor-29"));
        state.move(999);
        var last = Buffer.empty(area);
        view.render(area, last);
        assertTrue(text(last).contains("processor-29"));
        assertTrue(text(last).contains("jitter=4300 ns"));
        state.nextPage();
        var configuration = Buffer.empty(area);
        view.render(area, configuration);
        assertTrue(text(configuration).contains("CONFIGURATION"));
        assertTrue(text(configuration).contains("Application file: C:/config/application.yaml"));
        assertTrue(text(configuration).contains("destinations: [postSSB]"));
        assertFalse(text(configuration).contains("latest event"));
        state.nextPage();
        var activity = Buffer.empty(area);
        view.render(area, activity);
        assertTrue(text(activity).contains("ACTIVITY"));
        assertTrue(text(activity).contains("latest event"));
        assertTrue(text(activity).contains("log drops: 2"));
        state.nextPage();
        var again = Buffer.empty(area);
        view.render(area, again);
        assertTrue(text(again).contains("processor-00"));
    }

    private static String text(Buffer buffer) {
        return buffer.area()
            .positions()
            .map(position -> buffer.get(position)
                .symbol())
            .collect(Collectors.joining());
    }
}
