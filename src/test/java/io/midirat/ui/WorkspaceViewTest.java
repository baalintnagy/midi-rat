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
import io.midirat.midi.Envelope;
import io.midirat.midi.MidiMessage;
import org.junit.jupiter.api.Test;

class WorkspaceViewTest {
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
        assertTrue(text.contains("> 2/2"));
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
                ModuleWidget.fixed(() -> List.of("Chord: CM", "Note names: C, E, G"))),
            instance("emitter", ModuleWidget.fixed(() -> List.of("Messages: 0"))));
        for (int width : List.of(60, 160)) {
            var area = Rect.of(width, 30);
            var buffer = Buffer.empty(area);
            view(instances, 1, true).render(area, buffer);
            String rendered = text(buffer);
            int firstRow = rendered.indexOf("1/3") / (width + 1);
            int secondRow = rendered.indexOf("> 2/3") / (width + 1);
            int thirdRow = rendered.indexOf("3/3") / (width + 1);
            if (width == 160) {
                assertEquals(firstRow, secondRow);
                assertEquals(secondRow, thirdRow);
            } else {
                assertTrue(firstRow < secondRow);
                assertTrue(secondRow < thirdRow);
            }
            assertTrue(rendered.contains("Note names: C, E, G"));
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
        int monitorRow = rendered.indexOf("> 2/4") / (area.width() + 1);
        assertEquals("╭", buffer.get(0, monitorRow)
            .symbol());
        assertEquals("╮", buffer.get(area.width() - 1, monitorRow)
            .symbol());
        assertTrue(rendered.indexOf("1/4") < rendered.indexOf("> 2/4"));
        assertTrue(rendered.indexOf("> 2/4") < rendered.indexOf("3/4"));
        assertEquals(rendered.indexOf("3/4") / (area.width() + 1),
            rendered.indexOf("4/4") / (area.width() + 1));
        assertTrue(monitor.budget > 1);
    }

    @Test
    void hostStatusAndLatestActivityShareOneCompactPanel() {
        for (int width : List.of(80, 160)) {
            var area = Rect.of(width, 20);
            var buffer = Buffer.empty(area);
            new WorkspaceView(List.of("winmm | inputs=8", "postSSB [open]"), List.of(), 0,
                List.of("earlier event", "latest event"), 2,
                new WorkspaceView.Editor(false, "hex", ""), "", true).render(area, buffer);
            String rendered = text(buffer);
            assertTrue(rendered.contains("CONNECTIONS / ACTIVITY"));
            assertTrue(rendered.contains("log drops: 2"));
            assertTrue(rendered.contains("winmm | inputs=8"));
            assertTrue(rendered.contains("postSSB [open]"));
            assertTrue(rendered.contains("latest event"));
            assertFalse(rendered.contains("earlier event"));
            assertEquals(1, rendered.chars()
                .filter(symbol -> symbol == '╭')
                .count());
            int connectionRow = rendered.indexOf("winmm") / (width + 1);
            int activityRow = rendered.indexOf("latest event") / (width + 1);
            assertEquals(width >= 100 ? connectionRow : connectionRow + 2, activityRow);
        }
    }

    @Test
    void compactModeShowsFocusedWidgetWhileStillDrainingHiddenMonitors() {
        var hidden = new ElasticRows();
        var visible = new ElasticRows();
        var instances = List.of(instance("hidden-monitor", hidden),
            instance("visible-monitor", visible));
        var area = Rect.of(80, 14);
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
        assertTrue(text(buffer).contains("Plain color fallback"));
    }

    private static WorkspaceView view(List<Instance> instances, int focused, boolean colors) {
        return new WorkspaceView(List.of("winmm | inputs=8 outputs=1", "postSSB [open] queue=0"),
            instances, focused, List.of("Connected 8 inputs"), 0,
            new WorkspaceView.Editor(false, "hex", ""), "", colors);
    }

    @Test
    void compactEditorKeepsItsInputAndTheLatestDiagnosticVisible() {
        var instances = List.of(instance("hidden-monitor", new ElasticRows()),
            instance("visible-monitor", new ElasticRows()));
        var area = Rect.of(100, 20);
        var buffer = Buffer.empty(area);
        new WorkspaceView(List.of("winmm | inputs=8 outputs=1"), instances, 1,
            List.of("earlier event", "last route failed"), 0,
            new WorkspaceView.Editor(true, "hex", "90 3C 64"), "FA", true).render(area, buffer);
        assertTrue(text(buffer).contains("90 3C 64"));
        assertTrue(text(buffer).contains("last route failed"));
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
