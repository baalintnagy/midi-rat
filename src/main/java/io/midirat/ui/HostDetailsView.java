package io.midirat.ui;

import java.util.List;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.text.Line;
import dev.tamboui.widget.Widget;
import io.midirat.host.HostStatus;

/** Host details also occupy a dedicated screen rather than expanding the workspace. */
record HostDetailsView(HostStatus status, List<String> configuration, List<String> diagnostics,
    long drops, State state, boolean colors) implements Widget {
    private enum Page {
        STATUS, CONFIGURATION, ACTIVITY
    }

    static final class State {
        private Page page = Page.STATUS;
        private final HostPanel.Scroll scroll = new HostPanel.Scroll(0);

        void reset() {
            page = Page.STATUS;
            scroll.reset();
        }

        void nextPage() {
            page = switch (page) {
            case STATUS -> Page.CONFIGURATION;
            case CONFIGURATION -> Page.ACTIVITY;
            case ACTIVITY -> Page.STATUS;
            };
            scroll.reset();
        }

        void move(int delta) {
            scroll.move(delta);
        }
    }

    HostDetailsView {
        configuration = List.copyOf(configuration);
        diagnostics = List.copyOf(diagnostics);
    }

    @Override
    public void render(Rect area, Buffer buffer) {
        if (area.isEmpty()) {
            return;
        }
        int headerHeight = Math.min(2, area.height());
        WorkspaceView.paragraph(new Rect(area.x(), area.y(), area.width(), headerHeight), buffer,
            List.of(Line.from(ModuleTextStyle.span(" CONNECTIONS / ACTIVITY DETAILS",
                Color.LIGHT_CYAN, colors)),
                Line.from(" Esc/d/h back | m page | [/] scroll | log drops: %d".formatted(drops))));
        area = new Rect(area.x(), area.y() + headerHeight, area.width(),
            area.height() - headerHeight);
        int width = Math.max(0, area.width() - 4);
        List<Line> rows = switch (state.page) {
        case STATUS -> HostPanel.status(status, true, width, colors);
        case CONFIGURATION -> configuration.stream()
            .flatMap(line -> HostPanel.wrap(line, width, colors)
                .stream())
            .toList();
        case ACTIVITY ->
            (diagnostics.isEmpty() ? List.of("Activity: Ready.") : diagnostics).stream()
                .flatMap(line -> HostPanel.wrap(line, width, colors)
                    .stream())
                .toList();
        };
        int visible = Math.max(0, area.height() - 2);
        int offset = state.scroll.position(rows.size() - visible);
        WorkspaceView.panel(area, buffer, Line.from(ModuleTextStyle.span(
            " %s | m page | [/] %d-%d/%d ".formatted(
                state.page, rows.isEmpty() ? 0 : offset + 1,
                Math.min(rows.size(), offset + visible),
                rows.size()),
            Color.LIGHT_BLUE, colors)),
            rows.subList(offset, Math.min(rows.size(), offset + visible)), true, colors);
    }
}
