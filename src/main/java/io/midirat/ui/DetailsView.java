package io.midirat.ui;

import java.util.List;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.text.Line;
import dev.tamboui.widget.Widget;
import io.midirat.api.ModuleDetails;
import io.midirat.api.ModuleWidget;

/** The same full-screen detail layout works for built-ins and external providers. */
record DetailsView(String name, String type, ModuleDetails details, State state,
    boolean colors) implements Widget {
    static final class State {
        final HostPanel.Scroll information = new HostPanel.Scroll(0);
        final HostPanel.Scroll horizontal = new HostPanel.Scroll(0);

        void reset() {
            information.reset();
            horizontal.reset();
        }
    }

    @Override
    public void render(Rect area, Buffer buffer) {
        if (area.isEmpty()) {
            return;
        }
        boolean enabled = colors
            && details.content()
                .colors();
        List<Line> header = List.of(
            Line.from(ModuleTextStyle.span(" %s  ".formatted(name), Color.LIGHT_CYAN, enabled),
                ModuleTextStyle.span(type, Color.LIGHT_BLUE, enabled)),
            Line.from(
                " Esc/d back | m page | [/] rows | p pause | c clear | y copy | x raw | </> pan"));
        WorkspaceView.paragraph(new Rect(area.x(), area.y(), area.width(),
            Math.min(2, area.height())), buffer, header);
        int y = area.y() + Math.min(2, area.height());
        int width = Math.max(0, area.width() - 4);
        List<Line> information = details.information()
            .stream()
            .flatMap(line -> HostPanel.wrap(line, width, enabled)
                .stream())
            .toList();
        if (!information.isEmpty()
            && area.bottom() - y >= 6) {
            int height = Math.min(information.size() + 2, Math.max(3, (area.height() - 3) / 3));
            int visible = height - 2;
            int offset = state.information.position(information.size() - visible);
            Line title = Line.from(ModuleTextStyle.span(
                " %s  [/] %d-%d/%d ".formatted(details.informationTitle(), offset + 1,
                    Math.min(information.size(), offset + visible), information.size()),
                Color.LIGHT_BLUE, enabled));
            WorkspaceView.panel(new Rect(area.x(), y, area.width(), height), buffer, title,
                information.subList(offset, Math.min(information.size(), offset + visible)),
                true, enabled);
            y += height + 1;
        }
        renderContent(new Rect(area.x(), y, area.width(), Math.max(0, area.bottom() - y)), buffer);
    }

    private void renderContent(Rect area, Buffer buffer) {
        if (area.isEmpty()) {
            return;
        }
        ModuleWidget widget = details.content();
        boolean enabled = colors
            && widget.colors();
        int height = Math.max(0, area.height() - 2);
        int width = Math.max(0, area.width() - 4);
        if (!widget.elastic()) {
            List<Line> rows = widget.lines(height)
                .stream()
                .flatMap(line -> HostPanel.wrap(line, width, enabled)
                    .stream())
                .toList();
            int offset = state.information.position(rows.size() - height);
            WorkspaceView.panel(area, buffer, Line.from(ModuleTextStyle.span(
                " %s | m page | [/] %d-%d/%d ".formatted(details.mode(),
                    rows.isEmpty() ? 0 : offset + 1, Math.min(rows.size(), offset + height),
                    rows.size()),
                Color.LIGHT_BLUE, enabled)),
                rows.subList(offset, Math.min(rows.size(), offset + height)), true, enabled);
            return;
        }
        List<Line> rows = widget.lines(height)
            .stream()
            .map(line -> ModuleTextStyle.render(line, enabled))
            .toList();
        int fullWidth = Math.max(width, rows.stream()
            .mapToInt(Line::width)
            .max()
            .orElse(0));
        int offset = state.horizontal.position(fullWidth - width);
        WorkspaceView.panel(area, buffer, Line.from(ModuleTextStyle.span(
            " %s LOG  | m page | < > pan | column %d ".formatted(details.mode(), offset + 1),
            Color.LIGHT_BLUE, enabled)), List.of(), true, enabled);
        if (width == 0
            || height == 0) {
            return;
        }
        Buffer content = Buffer.empty(Rect.of(fullWidth, height));
        WorkspaceView.paragraph(content.area(), content, rows);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Cell cell = content.get(x + offset, y);
                buffer.set(area.x() + x + 2, area.y() + y + 1,
                    x == 0
                        && cell.isContinuation() ? Cell.EMPTY : cell);
            }
        }
    }
}
