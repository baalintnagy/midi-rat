package io.midirat.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import dev.tamboui.text.Line;
import io.midirat.host.HostStatus;
import io.midirat.midi.Endpoint;
import org.junit.jupiter.api.Test;

class HostPanelTest {
    @Test
    void scrollingClampsAtBothEndsAndAfterResize() {
        var scroll = new HostPanel.Scroll(0);
        scroll.position(6);
        scroll.move(100);
        assertEquals(6, scroll.position(6));
        scroll.move(-3);
        assertEquals(3, scroll.position(6));
        assertEquals(1, scroll.position(1));
        scroll.move(-3);
        assertEquals(0, scroll.position(1));
    }

    @Test
    void longDeviceNamesTimingAndEndpointAliasesWrapWithoutLosingTheirTails() {
        String port = "Very long hardware port name with 日本語 and a distinctive END-OF-PORT";
        String latency = "submission n=128 median=2200 p95=4100 p99=6500 max=8100 jitter=4300 ns";
        var status = new HostStatus(new HostStatus.Devices("windows-midi-services", 8, 1,
            latency, List.of(new HostStatus.Connection("postSSB", port, true, 0, 0))),
            List.of(new Endpoint("last-input-alias", Endpoint.Kind.INPUT),
                new Endpoint("last-bus-alias", Endpoint.Kind.BUS)),
            List.of(new HostStatus.Route("sostenuto", true, 0, 0)));
        for (int width : List.of(10, 37, 76, 156)) {
            var rows = HostPanel.status(status, true, width, true);
            String text = rows.stream()
                .map(Line::rawContent)
                .collect(Collectors.joining());
            assertTrue(text.contains(port));
            assertTrue(text.contains(latency));
            assertTrue(text.contains("last-input-alias"));
            assertTrue(text.contains("last-bus-alias"));
            assertFalse(text.contains("Endpoint["));
            assertTrue(rows.stream()
                .allMatch(row -> row.width() <= width));
        }
    }

    @Test
    void summaryContainsOnlyBackendOutputsAndModuleTotals() {
        var status = new HostStatus(new HostStatus.Devices("winmm", 2, 0, "No samples", List.of()),
            List.of(), List.of(new HostStatus.Route("healthy-observer", true, 0, 0),
                new HostStatus.Route("failed-processor", false, 0, 0),
                new HostStatus.Route("overloaded-monitor", true, 4, 7)));
        String text = HostPanel.status(status, false, 120, false)
            .stream()
            .map(Line::rawContent)
            .collect(Collectors.joining("\n"));
        assertTrue(text.contains("2 active, 1 disabled | queue=4 lost=7"));
        assertTrue(text.contains("Backend:"));
        assertTrue(text.contains("Output: --"));
        assertFalse(text.contains("failed-processor"));
        assertFalse(text.contains("overloaded-monitor"));
        assertFalse(text.contains("healthy-observer"));
        assertFalse(text.contains("Timing:"));
    }
}
