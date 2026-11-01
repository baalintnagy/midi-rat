package io.midirat.ui;

import static dev.tamboui.text.Span.raw;
import static io.midirat.ui.ModuleTextStyle.span;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Padding;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Overflow;
import dev.tamboui.text.Line;
import dev.tamboui.text.Text;
import dev.tamboui.widget.Widget;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.midirat.api.ModuleWidget;
import io.midirat.config.NetworkConfig.Instance;
import io.midirat.host.HostStatus;

/** Bordered terminal presentation, independent of host commands and MIDI processing. */
record WorkspaceView(HostStatus status, List<Instance> instances, int focused,
    Editor editor, String copied, boolean colors) implements Widget {
    record Editor(boolean active, String format, String text) {
    }

    private record Pane(Instance instance, ModuleWidget widget, List<String> rows) {
    }

    WorkspaceView {
        instances = List.copyOf(instances);
    }

    @Override
    public void render(Rect area, Buffer buffer) {
        if (area.isEmpty()) {
            return;
        }
        List<Line> header = header(area.width());
        int hostWidth = Math.max(0, area.width() - 4);
        List<Line> connections = HostPanel.status(status, false, hostWidth, colors);
        List<Pane> panes = new ArrayList<>();
        for (Instance instance : instances) {
            ModuleWidget widget = instance.module()
                .widget();
            // Drain hidden monitors too, preserving their history in compact mode.
            List<String> rows = widget.lines(widget.elastic() ? 1 : area.height());
            panes.add(new Pane(instance, widget, rows));
        }
        List<ModuleLayout.Item> items = new ArrayList<>();
        for (int i = 0; i < panes.size(); i++) {
            Pane pane = panes.get(i);
            items.add(new ModuleLayout.Item(i, preferredWidth(pane, i), pane.rows()
                .size(),
                pane.widget()
                    .elastic()));
        }
        List<ModuleLayout.Row> layout = ModuleLayout.rows(items, area.width());
        int elasticCount = (int) layout.stream()
            .filter(ModuleLayout.Row::elastic)
            .count();
        int fixedRows = layout.stream()
            .filter(row -> !row.elastic())
            .mapToInt(ModuleLayout.Row::height)
            .sum();
        int preferredHostHeight = connections.size() + 2;
        int hostLimit = panes.isEmpty() ? area.height() - header.size()
            : Math.max(5, area.height() / 3);
        int hostHeight = Math.max(0, Math.min(preferredHostHeight,
            Math.min(hostLimit, area.height() - header.size() - (panes.isEmpty() ? 0 : 3))));
        int chrome = layout.size() * 2 + Math.max(0, layout.size() - 1);
        int available = area.height() - header.size() - hostHeight - 1 - chrome - fixedRows;
        boolean compact = !panes.isEmpty()
            && available < elasticCount;
        if (compact) {
            renderCompact(area, buffer, panes, header, connections);
            return;
        }
        int y = area.y();
        paragraph(new Rect(area.x(), y, area.width(), header.size()), buffer, header);
        y += header.size();
        int elasticRows = elasticCount == 0 ? 0 : available / elasticCount;
        for (ModuleLayout.Row row : layout) {
            int count = row.elastic() ? elasticRows : row.height();
            int spare = area.width() - row.width();
            int columns = row.items()
                .size();
            int x = area.x();
            for (int column = 0; column < columns; column++) {
                ModuleLayout.Item item = row.items()
                    .get(column);
                int width = item.width() + spare / columns + (column < spare % columns ? 1 : 0);
                renderPane(new Rect(x, y, width, count + 2), buffer,
                    panes.get(item.index()), item.index(), count);
                x += width + ModuleLayout.COLUMN_GAP;
            }
            y += count + 3;
        }
        renderHost(new Rect(area.x(), area.bottom() - hostHeight, area.width(), hostHeight),
            buffer, connections);
    }

    private void renderCompact(Rect area, Buffer buffer, List<Pane> panes,
        List<Line> header, List<Line> connections) {
        int headerHeight = Math.min(area.height(), area.height() < 12 ? 1 : header.size());
        paragraph(new Rect(area.x(), area.y(), area.width(), headerHeight), buffer, header);
        int y = area.y() + headerHeight;
        int index = Math.floorMod(focused, panes.size() + 1);
        if (index == panes.size()) {
            renderHost(new Rect(area.x(), y, area.width(), Math.max(0, area.bottom() - y)),
                buffer, connections);
            return;
        }
        int footerHeight = area.height() >= 12
            ? Math.min(connections.size() + 2, Math.min(area.height() / 2, 6))
            : 0;
        int paneHeight = Math.max(0, area.bottom() - y - footerHeight
            - (footerHeight > 0 ? 1 : 0));
        renderPane(new Rect(area.x(), y, area.width(), paneHeight), buffer,
            panes.get(index), index, Math.max(0, paneHeight - 2));
        if (footerHeight > 0) {
            renderHost(new Rect(area.x(), area.bottom() - footerHeight, area.width(), footerHeight),
                buffer, connections);
        }
    }

    private void renderHost(Rect area, Buffer buffer, List<Line> connections) {
        boolean selected = Math.floorMod(focused, instances.size() + 1) == instances.size();
        Line title = Line.from(span(" %s %d/%d  CONNECTIONS / ACTIVITY ".formatted(
            selected ? ">" : " ", instances.size() + 1, instances.size() + 1),
            selected ? Color.LIGHT_CYAN : Color.WHITE, colors));
        panel(area, buffer, title, connections, selected, colors);
    }

    private int preferredWidth(Pane pane, int index) {
        int contentWidth = pane.rows()
            .stream()
            .map(Line::from)
            .mapToInt(Line::width)
            .max()
            .orElse(0) + 4;
        return Math.max(24,
            Math.max(contentWidth, paneTitle(pane, index, true, false).width() + 2));
    }

    private Line paneTitle(Pane pane, int index, boolean selected, boolean enabled) {
        Line title = Line.from(
            span(" %s %d/%d  %s  ".formatted(selected ? ">" : " ", index + 1,
                instances.size() + 1, pane.instance()
                    .name()),
                selected ? Color.LIGHT_CYAN : Color.WHITE, enabled),
            span("%s ".formatted(pane.instance()
                .type()), Color.LIGHT_BLUE, enabled));
        if (enabled) {
            title = title.bold();
        }
        return title;
    }

    private void renderPane(Rect area, Buffer buffer, Pane pane, int index, int contentRows) {
        boolean selected = index == Math.floorMod(focused, instances.size() + 1);
        boolean enabled = colors
            && pane.widget()
                .colors();
        List<String> rows = pane.widget()
            .elastic()
                ? pane.widget()
                    .lines(contentRows)
                : pane.rows();
        panel(area, buffer, paneTitle(pane, index, selected, enabled),
            styled(rows, enabled), selected, enabled);
    }

    static void panel(Rect area, Buffer buffer, Line title, List<Line> rows,
        boolean focused, boolean colors) {
        area = area.intersection(buffer.area());
        if (area.isEmpty()) {
            return;
        }
        Block.Builder builder = Block.builder()
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .padding(Padding.horizontal(1));
        if (colors) {
            builder.borderColor(focused ? Color.LIGHT_CYAN : Color.rgb(64, 64, 64));
        }
        Block block = builder.build();
        Paragraph.builder()
            .block(block)
            .text(Text.from(rows))
            .overflow(Overflow.CLIP)
            .build()
            .render(area, buffer);
        // TamboUI 0.6 applies border color over title spans. Compose the title separately
        // so its module-name/type accents remain independent of the border.
        int titleWidth = Math.min(title.width(), Math.max(0, area.width() - 2));
        if (titleWidth > 0) {
            Paragraph.builder()
                .text(Text.from(title))
                .overflow(Overflow.ELLIPSIS)
                .build()
                .render(new Rect(area.x() + 1, area.y(), titleWidth, 1), buffer);
        }
    }

    static void paragraph(Rect area, Buffer buffer, List<Line> rows) {
        area = area.intersection(buffer.area());
        Paragraph.builder()
            .text(Text.from(rows))
            .overflow(Overflow.CLIP)
            .build()
            .render(area, buffer);
    }

    private static List<Line> styled(List<String> rows, boolean colors) {
        return rows.stream()
            .map(row -> ModuleTextStyle.render(row, colors))
            .toList();
    }

    private List<Line> header(int width) {
        Line brand = Line.from(span(" MIDI Rat ", Color.LIGHT_CYAN, colors),
            span(" /  MIDI workspace", Color.DARK_GRAY, colors));
        if (colors) {
            brand = brand.bold();
        }
        List<Line> lines = new ArrayList<>(List.of(brand,
            Line.from(span(" Tab/j/k", Color.LIGHT_CYAN, colors), raw(" focus   "),
                span("P / Ctrl+P", Color.LIGHT_RED, colors), raw(" panic   "),
                span("r", Color.LIGHT_CYAN, colors), raw(" reload   "),
                span("R", Color.LIGHT_CYAN, colors), raw(" reconnect   "),
                span("q", Color.LIGHT_CYAN, colors), raw(" exit")),
            Line.from(span(" p/c/d/y", Color.LIGHT_CYAN, colors),
                raw(" pause/clear/details/copy   "),
                span("e/v/Enter/s", Color.LIGHT_CYAN, colors),
                raw(" edit/paste/send/save"))));
        if (!colors) {
            lines.add(2, Line.from(" Plain color fallback: terminal/NO_COLOR"));
        }
        if (editor.active()) {
            lines.addAll(HostPanel.wrap(
                "EDIT %s | Ctrl+T format | Enter accept | Esc cancel".formatted(editor.format()),
                width, colors));
            lines.addAll(HostPanel.wrap(editor.text(), width, colors));
        }
        if (!copied.isEmpty()) {
            lines.addAll(HostPanel.wrap("Copy: %s".formatted(copied), width, colors));
        }
        lines.add(Line.empty());
        return lines;
    }

}
