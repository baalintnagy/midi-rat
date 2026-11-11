package io.midirat.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import io.midirat.api.ModuleInstance;
import io.midirat.api.ModuleWidget;
import io.midirat.config.NetworkConfig.Instance;
import io.midirat.host.HostStatus;
import io.midirat.midi.Envelope;
import io.midirat.midi.MidiMessage;
import org.junit.jupiter.api.Test;

class WorkspaceViewTest {
    @Test
    void hiddenPageMonitorsDrainAndHostFocusKeepsThePage() {
        var monitors = IntStream.range(0, 4)
            .mapToObj(index -> new ElasticRows())
            .toList();
        var instances = IntStream.range(0, 4)
            .mapToObj(index -> instance("monitor-%d".formatted(index), monitors.get(index)))
            .toList();
        var pages = new WorkspaceView.Pages();
        var editor = new WorkspaceView.Editor(false, "hex", "");
        var area = Rect.of(80, 25);
        var buffer = Buffer.empty(area);
        new WorkspaceView(hostStatus(), instances, 3, editor, "", false, pages)
            .render(area, buffer);
        assertTrue(text(buffer).contains("Page 2/2"));
        assertEquals(1, monitors.getFirst().budget);
        assertTrue(monitors.getLast().budget >= 4);
        var host = Buffer.empty(area);
        new WorkspaceView(hostStatus(), instances, 4, editor, "", false, pages)
            .render(area, host);
        assertTrue(text(host).contains("Page 2/2"));
        assertTrue(text(host).contains("> CONNECTIONS / ACTIVITY"));
        assertEquals(0, pages.move(1, 4));
        assertEquals(2, pages.move(-1, 0));
    }

    @Test
    void automaticPagesFollowFocusAndRepackAfterResize() {
        var instances = IntStream.range(0, 6)
            .mapToObj(index -> instance("module-%d".formatted(index),
                ModuleWidget.fixed(() -> List.of("Setting: on"))))
            .toList();
        var pages = new WorkspaceView.Pages();
        var editor = new WorkspaceView.Editor(false, "hex", "");
        var small = Rect.of(30, 20);
        var first = Buffer.empty(small);
        new WorkspaceView(hostStatus(), instances, 0, editor, "", false, pages)
            .render(small, first);
        assertTrue(text(first).contains("Page 1/3"));
        assertTrue(text(first).contains("module-1"));
        assertFalse(text(first).contains("module-2"));
        assertEquals(2, pages.move(1, 0));
        var second = Buffer.empty(small);
        new WorkspaceView(hostStatus(), instances, 2, editor, "", false, pages)
            .render(small, second);
        assertTrue(text(second).contains("Page 2/3"));
        assertTrue(text(second).contains("> module-2"));
        assertEquals(0, pages.move(-1, 2));
        var large = Rect.of(160, 40);
        var expanded = Buffer.empty(large);
        new WorkspaceView(hostStatus(), instances, 2, editor, "", false, pages)
            .render(large, expanded);
        assertFalse(text(expanded).contains("Page "));
        assertTrue(text(expanded).contains("> module-2"));
        assertTrue(text(expanded).contains("module-5"));
    }

    @Test
    void titleIsFocusableWithoutInlineKeyboardHelp() {
        var area = Rect.of(100, 20);
        var buffer = Buffer.empty(area);
        view(List.of(), 1, true).render(area, buffer);
        assertTrue(text(buffer).contains("> MIDI Rat"));
        assertTrue(text(buffer).contains("(d: open details)"));
        assertTrue(IntStream.range(0, area.width())
            .anyMatch(x -> buffer.get(x, 0)
                .symbol()
                .equals("(")
                && buffer.get(x, 0)
                    .style()
                    .fg()
                    .filter(Color.DARK_GRAY::equals)
                    .isPresent()));
        assertTrue(IntStream.range(0, area.width())
            .allMatch(x -> buffer.get(x, 1)
                .symbol()
                .equals("─")
                && buffer.get(x, 1)
                    .style()
                    .fg()
                    .filter(Color.rgb(64, 64, 64)::equals)
                    .isPresent()));
        assertFalse(text(buffer).contains("pause/clear/details/copy"));
        assertFalse(text(buffer).contains("Tab/j/k"));
    }

    @Test
    void keyboardHelpShowsShortcutsAndScrollsInSmallTerminals() {
        var area = Rect.of(120, 40);
        var buffer = Buffer.empty(area);
        var scroll = new HostPanel.Scroll(0);
        new KeyboardHelpView(scroll, false).render(area, buffer);
        assertTrue(text(buffer).contains("Keyboard help"));
        assertTrue(text(buffer).contains("Ctrl+P"));
        assertTrue(text(buffer).contains("Ctrl+T"));
        var small = Rect.of(45, 8);
        var compact = Buffer.empty(small);
        new KeyboardHelpView(scroll, false).render(small, compact);
        scroll.move(100);
        new KeyboardHelpView(scroll, false).render(small, compact);
        assertTrue(text(compact).contains("Esc / d back"));
    }

    @Test
    void syntaxAccentsPreserveTextAndNoColorRemovesStyling() {
        String text = "Pressed: 3 | Umbrella: on | queue=0";
        var styled = ModuleTextStyle.render(text, true);
        assertEquals(text, styled.rawContent());
        assertTrue(styled.spans()
            .stream()
            .anyMatch(span -> span.content()
                .equals("3")
                && span.style()
                    .fg()
                    .orElseThrow()
                    .equals(Color.LIGHT_YELLOW)));
        assertTrue(styled.spans()
            .stream()
            .anyMatch(span -> span.content()
                .equals("on")
                && span.style()
                    .fg()
                    .orElseThrow()
                    .equals(Color.LIGHT_GREEN)));
        var plain = ModuleTextStyle.render(text, false);
        assertEquals(text, plain.rawContent());
        assertTrue(plain.spans()
            .stream()
            .allMatch(span -> span.style()
                .fg()
                .isEmpty()));
        String midi = "CH: 1 | NOTE_ON | D1:60 | D2:100 | Status:144 | keys | bus | 1 ns | Note:C";
        assertEquals(MonitorRowStyle.render(midi, true), ModuleTextStyle.render(midi, true));
    }

    @Test
    void borderedMonitorsShareRowsAndFocusedBorderHasAnAccent() {
        var first = new ElasticRows();
        var second = new ElasticRows();
        var instances = List.of(instance("first-monitor", first),
            instance("second-monitor", second));
        var area = Rect.of(160, 40);
        var buffer = Buffer.empty(area);
        view(instances, 1, true).render(area, buffer);
        assertTrue(first.budget > 1);
        assertEquals(first.budget, second.budget);
        String text = text(buffer);
        assertTrue(text.contains("first-monitor"));
        assertTrue(text.contains("second-monitor"));
        assertTrue(text.contains("> second-monitor"));
        assertTrue(text.contains("╭"));
        assertTrue(area.positions()
            .anyMatch(position -> buffer.get(position)
                .style()
                .fg()
                .filter(Color.LIGHT_BLUE::equals)
                .isPresent()));
        assertTrue(IntStream.range(0, area.height())
            .anyMatch(y -> buffer.get(0, y)
                .symbol()
                .equals("╭")
                && buffer.get(0, y)
                    .style()
                    .fg()
                    .orElseThrow()
                    .equals(Color.LIGHT_CYAN)));
    }

    @Test
    void compactWidgetsShareColumnsAndStackWhenTheTerminalNarrows() {
        var instances = List.of(
            instance("filter", ModuleWidget.fixed(() -> List.of("Filter: 6 rules"))),
            instance("chord",
                ModuleWidget.fixed(() -> List.of("Chord: CM", "Notes: C, E, G"))),
            instance("emitter", ModuleWidget.fixed(() -> List.of("Messages: 0"))));
        for (int width : List.of(40, 160)) {
            var area = Rect.of(width, 30);
            var buffer = Buffer.empty(area);
            view(instances, 1, true).render(area, buffer);
            String rendered = text(buffer);
            int firstRow = rendered.indexOf(" filter ") / (width + 1);
            int secondRow = rendered.indexOf("> chord") / (width + 1);
            int thirdRow = rendered.indexOf(" emitter ") / (width + 1);
            if (width == 160) {
                assertEquals(firstRow, secondRow);
                assertEquals(secondRow, thirdRow);
            } else {
                assertTrue(firstRow < secondRow);
                assertTrue(secondRow < thirdRow);
            }
            assertTrue(rendered.contains("Notes: C, E, G"));
            assertTrue(rendered.contains("Messages: 0"));
            assertEquals(4, rendered.chars()
                .filter(symbol -> symbol == '╭')
                .count());
        }
    }

    @Test
    void monitorsKeepTheirOwnFullWidthRowsBetweenCompactWidgets() {
        var monitor = new ElasticRows();
        var instances = List.of(
            instance("filter", ModuleWidget.fixed(() -> List.of("Filter: 6 rules"))),
            instance("monitor", monitor),
            instance("chord", ModuleWidget.fixed(() -> List.of("Chord: CM"))),
            instance("emitter", ModuleWidget.fixed(() -> List.of("Messages: 0"))));
        var area = Rect.of(160, 30);
        var buffer = Buffer.empty(area);
        view(instances, 1, true).render(area, buffer);
        String rendered = text(buffer);
        int monitorRow = rendered.indexOf("> monitor") / (area.width() + 1);
        assertEquals("╭", buffer.get(0, monitorRow)
            .symbol());
        assertEquals("╮", buffer.get(area.width() - 1, monitorRow)
            .symbol());
        assertTrue(rendered.indexOf(" filter ") < rendered.indexOf("> monitor"));
        assertTrue(rendered.indexOf("> monitor") < rendered.indexOf(" chord "));
        assertEquals(rendered.indexOf(" chord ") / (area.width() + 1),
            rendered.indexOf(" emitter ") / (area.width() + 1));
        assertTrue(monitor.budget > 1);
    }

    @Test
    void hostSummaryIsFocusableAndShowsBackendCountsAndModuleTotals() {
        for (int width : List.of(80, 160)) {
            var area = Rect.of(width, 20);
            var buffer = Buffer.empty(area);
            new WorkspaceView(hostStatus(), List.of(), 0,
                new WorkspaceView.Editor(false, "hex", ""), "", true).render(area, buffer);
            String rendered = text(buffer);
            assertTrue(rendered.contains("CONNECTIONS / ACTIVITY"));
            assertFalse(rendered.contains("d details"));
            assertTrue(rendered.contains("> CONNECTIONS / ACTIVITY"));
            assertTrue(rendered.contains("Backend: winmm | inputs=8 outputs=1"));
            assertFalse(rendered.contains("Output:"));
            assertTrue(rendered.contains("Modules:"));
            assertFalse(rendered.contains("Timing:"));
            assertFalse(rendered.contains("Activity:"));
            assertEquals(1, rendered.chars()
                .filter(symbol -> symbol == '╭')
                .count());
            assertTrue(area.positions()
                .anyMatch(position -> buffer.get(position)
                    .symbol()
                    .equals("╭")
                    && buffer.get(position)
                        .style()
                        .fg()
                        .orElseThrow()
                        .equals(Color.LIGHT_CYAN)));
        }
    }

    @Test
    void compactHostWidgetHidesConnectionDetails() {
        var status = new HostStatus(new HostStatus.Devices("windows-midi-services", 8, 1,
            "submission n=128 median=2200 p95=4100 p99=6500 max=8100 jitter=4300 ns",
            List.of(new HostStatus.Connection("postSSB",
                "A long physical hardware device name with several endpoint groups END-OF-PORT",
                true, 0, 0))),
            List.of(), List.of());
        var area = Rect.of(80, 24);
        var buffer = Buffer.empty(area);
        new WorkspaceView(status, List.of(), 0,
            new WorkspaceView.Editor(false, "hex", ""), "", true).render(area, buffer);
        String rendered = text(buffer);
        assertFalse(rendered.contains("END-OF-PORT"));
        assertFalse(rendered.contains("jitter=4300"));
    }

    @Test
    void compactTerminalCanFocusTheHostWhileStillDrainingHiddenMonitors() {
        var hidden = new ElasticRows();
        var instances = List.of(instance("hidden-monitor", hidden));
        var area = Rect.of(80, 8);
        var buffer = Buffer.empty(area);
        view(instances, 1, true).render(area, buffer);
        assertEquals(1, hidden.budget);
        assertTrue(text(buffer).contains("> CONNECTIONS / ACTIVITY"));
        assertTrue(text(buffer).contains("Backend:"));
        assertFalse(text(buffer).contains("hidden-monitor"));
    }

    @Test
    void compactModeShowsFocusedWidgetWhileStillDrainingHiddenMonitors() {
        var hidden = new ElasticRows();
        var visible = new ElasticRows();
        var instances = List.of(instance("hidden-monitor", hidden),
            instance("visible-monitor", visible));
        var area = Rect.of(80, 10);
        var buffer = Buffer.empty(area);
        view(instances, 1, true).render(area, buffer);
        assertEquals(1, hidden.budget);
        assertTrue(visible.budget > 1);
        assertFalse(text(buffer).contains("hidden-monitor"));
        assertTrue(text(buffer).contains("visible-monitor"));
    }

    @Test
    void plainFallbackAndVerySmallTerminalsRemainRenderable() {
        var instances = List.of(instance("monitor", new ElasticRows()));
        for (var area : List.of(Rect.of(1, 1), Rect.of(20, 5), Rect.of(80, 18))) {
            var buffer = Buffer.empty(area);
            view(instances, 0, false).render(area, buffer);
            area.positions()
                .forEach(position -> {
                    assertTrue(buffer.get(position)
                        .style()
                        .fg()
                        .isEmpty());
                    assertTrue(buffer.get(position)
                        .style()
                        .bg()
                        .isEmpty());
                });
        }
        var area = Rect.of(80, 18);
        var buffer = Buffer.empty(area);
        view(instances, 0, false).render(area, buffer);
        assertFalse(text(buffer).contains("Tab/j/k"));
    }

    private static WorkspaceView view(List<Instance> instances, int focused, boolean colors) {
        return new WorkspaceView(hostStatus(),
            instances, focused,
            new WorkspaceView.Editor(false, "hex", ""), "", colors);
    }

    @Test
    void compactEditorKeepsItsInputOutsideTheHostSummary() {
        var instances = List.of(instance("hidden-monitor", new ElasticRows()),
            instance("visible-monitor", new ElasticRows()));
        var area = Rect.of(100, 20);
        var buffer = Buffer.empty(area);
        new WorkspaceView(hostStatus(), instances, 1,
            new WorkspaceView.Editor(true, "hex", "90 3C 64"), "FA", true).render(area, buffer);
        assertTrue(text(buffer).contains("90 3C 64"));
        assertTrue(text(buffer).contains("Copy: FA"));
        assertTrue(text(buffer).contains("visible-monitor"));
    }

    private static Instance instance(String name, ModuleWidget widget) {
        ModuleInstance module = new ModuleInstance() {
            @Override
            public List<MidiMessage> process(Envelope input) {
                return List.of();
            }

            @Override
            public ModuleWidget widget() {
                return widget;
            }
        };
        return new Instance(name, "midi-monitor", List.of(), List.of(), module);
    }

    private static HostStatus hostStatus() {
        return new HostStatus(new HostStatus.Devices("winmm", 8, 1,
            "No output latency samples",
            List.of(new HostStatus.Connection("postSSB", "postSSB", true, 0, 0))),
            List.of(), List.of());
    }

    private static String text(Buffer buffer) {
        StringBuilder text = new StringBuilder();
        for (int y = 0; y < buffer.height(); y++) {
            for (int x = 0; x < buffer.width(); x++) {
                text.append(buffer.get(x, y)
                    .symbol());
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static final class ElasticRows implements ModuleWidget {
        private int budget;

        @Override
        public boolean elastic() {
            return true;
        }

        @Override
        public List<String> lines(int availableLines) {
            budget = availableLines;
            return IntStream.range(0, Math.max(0, availableLines))
                .mapToObj(i -> "Row %d".formatted(i))
                .toList();
        }
    }
}
