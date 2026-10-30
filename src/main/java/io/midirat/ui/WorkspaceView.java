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

/** Bordered terminal presentation, independent of host commands and MIDI processing. */
record WorkspaceView(List<String> status, List<Instance> instances, int focused,
    List<String> diagnostics, long droppedDiagnostics, Editor editor, String copied,
    boolean colors) implements Widget {
    record Editor(boolean active, String format, String text) {
    }

    private record Pane(Instance instance, ModuleWidget widget, List<String> rows) {
    }

    WorkspaceView {
        status = List.copyOf(status);
        instances = List.copyOf(instances);
        diagnostics = List.copyOf(diagnostics);
    }

    @Override
    public void render(Rect area, Buffer buffer) {
        if (area.isEmpty()) {
            return;
        }
        List<Line> header = header();
        List<Line> activity = activity();
        List<Pane> panes = new ArrayList<>();
        int elasticCount = 0;
        int fixedRows = 0;
        for (Instance instance : instances) {
            ModuleWidget widget = instance.module()
                .widget();
            // Drain hidden monitors too, preserving their history in compact mode.
            List<String> rows = widget.lines(widget.elastic() ? 1 : area.height());
            panes.add(new Pane(instance, widget, rows));
            if (widget.elastic()) {
                elasticCount++;
            } else {
                fixedRows += rows.size();
            }
        }
        int connectionsHeight = status.isEmpty() ? 0 : status.size() + 3;
        int activityHeight = activity.size() + 2;
        int chrome = panes.size() * 2 + Math.max(0, panes.size() - 1);
        int available = area.height() - header.size() - connectionsHeight
            - activityHeight - 1 - chrome - fixedRows;
        boolean compact = !panes.isEmpty()
            && available < elasticCount;
        if (compact) {
            renderCompact(area, buffer, panes, header, activity);
            return;
        }
        int y = area.y();
        paragraph(new Rect(area.x(), y, area.width(), header.size()), buffer, header);
        y += header.size();
        if (!status.isEmpty()) {
            panel(new Rect(area.x(), y, area.width(), status.size() + 2), buffer,
                Line.from(span(" CONNECTIONS ", Color.LIGHT_BLUE, colors)), styled(status, colors),
                false, colors);
            y += connectionsHeight;
        }
        int elasticRows = elasticCount == 0 ? 0 : available / elasticCount;
        for (int i = 0; i < panes.size(); i++) {
            Pane pane = panes.get(i);
            int count = pane.widget()
                .elastic() ? elasticRows
                    : pane.rows()
                        .size();
            renderPane(new Rect(area.x(), y, area.width(), count + 2), buffer, pane, i, count);
            y += count + 3;
        }
        panel(new Rect(area.x(), area.bottom() - activityHeight, area.width(), activityHeight),
            buffer, activityTitle(), activity, false, colors);
    }

    private void renderCompact(Rect area, Buffer buffer, List<Pane> panes,
        List<Line> header, List<Line> activity) {
        int headerHeight = Math.min(area.height(), area.height() < 12 ? 1 : 3);
        paragraph(new Rect(area.x(), area.y(), area.width(), headerHeight), buffer, header);
        int y = area.y() + headerHeight;
        if (area.height() >= 18
            && !status.isEmpty()) {
            panel(new Rect(area.x(), y, area.width(), 3), buffer,
                Line.from(span(" CONNECTIONS ", Color.LIGHT_BLUE, colors)),
                styled(status.subList(0, 1), colors), false, colors);
            y += 4;
        }
        int footerHeight = area.height() >= 12 ? Math.min(activity.size() + 2, 5) : 0;
        int paneHeight = Math.max(0, area.bottom() - y - footerHeight
            - (footerHeight > 0 ? 1 : 0));
        int index = Math.floorMod(focused, panes.size());
        renderPane(new Rect(area.x(), y, area.width(), paneHeight), buffer,
            panes.get(index), index, Math.max(0, paneHeight - 2));
        if (footerHeight > 0) {
            panel(new Rect(area.x(), area.bottom() - footerHeight, area.width(), footerHeight),
                buffer, activityTitle(), compactActivity(activity, footerHeight - 2), false,
                colors);
        }
    }

    private List<Line> compactActivity(List<Line> activity, int capacity) {
        int pinnedRows = editor.active() ? 2 : copied.isEmpty() ? 0 : 1;
        List<Line> rows = new ArrayList<>(activity.subList(0, Math.min(pinnedRows, capacity)));
        int tail = Math.max(rows.size(), activity.size() - (capacity - rows.size()));
        rows.addAll(activity.subList(tail, activity.size()));
        return rows;
    }

    private void renderPane(Rect area, Buffer buffer, Pane pane, int index, int contentRows) {
        boolean selected = index == Math.floorMod(focused, instances.size());
        boolean enabled = colors
            && pane.widget()
                .colors();
        Line title = Line.from(
            span(" %s %d/%d  %s  ".formatted(selected ? ">" : " ", index + 1,
                instances.size(), pane.instance()
                    .name()),
                selected ? Color.LIGHT_CYAN : Color.WHITE, enabled),
            span("%s ".formatted(pane.instance()
                .type()), Color.LIGHT_MAGENTA, enabled));
        if (enabled) {
            title = title.bold();
        }
        List<String> rows = pane.widget()
            .elastic()
                ? pane.widget()
                    .lines(contentRows)
                : pane.rows();
        panel(area, buffer, title, styled(rows, enabled), selected, enabled);
    }

    private static void panel(Rect area, Buffer buffer, Line title, List<Line> rows,
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

    private static void paragraph(Rect area, Buffer buffer, List<Line> rows) {
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

    private List<Line> header() {
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
        lines.add(Line.empty());
        return lines;
    }

    private Line activityTitle() {
        return Line.from(span(" ACTIVITY ", Color.LIGHT_BLUE, colors),
            span(" log drops: %d ".formatted(droppedDiagnostics),
                droppedDiagnostics == 0 ? Color.DARK_GRAY : Color.LIGHT_RED, colors));
    }

    private List<Line> activity() {
        List<Line> lines = new ArrayList<>();
        if (editor.active()) {
            lines.add(ModuleTextStyle.render(
                "EDIT %s | Ctrl+T format | Enter accept | Esc cancel".formatted(editor.format()),
                colors));
            lines.add(ModuleTextStyle.render(editor.text(), colors));
        }
        if (!copied.isEmpty()) {
            lines.add(ModuleTextStyle.render("Copy: %s".formatted(copied), colors));
        }
        diagnostics.stream()
            .skip(Math.max(0, diagnostics.size() - 3))
            .map(row -> ModuleTextStyle.render(row, colors))
            .forEach(lines::add);
        if (lines.isEmpty()) {
            lines.add(Line.from(span("Ready.", Color.DARK_GRAY, colors)));
        }
        return lines;
    }
}
