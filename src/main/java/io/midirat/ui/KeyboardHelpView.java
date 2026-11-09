package io.midirat.ui;

import java.util.List;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.text.Line;
import dev.tamboui.widget.Widget;

/** Full-screen help for the focusable workspace title. */
record KeyboardHelpView(HostPanel.Scroll scroll, boolean colors) implements Widget {
    private static final List<String> SHORTCUTS = List.of(
        "WORKSPACE",
        "Tab / j: next focus | k: previous focus (including the MIDI Rat title)",
        "d: open focused widget details; title details show this help",
        "Left/Right: previous/next workspace page (focus follows automatically)",
        "h: Connections / Activity details",
        "r: reload network | R: reconnect devices",
        "P / Ctrl+P: explicit Panic on all buses and physical outputs",
        "q / Ctrl+C: exit",
        "",
        "DETAILS",
        "Esc / d: back | m / Tab: next page (module/host details)",
        "[ / ]: scroll information | < / >: pan wide message logs",
        "p: pause/resume | c: clear | y: copy selected monitor/log message",
        "x: toggle raw/decoded log payload details",
        "h: back from host details",
        "",
        "EMITTER",
        "e: edit | v: paste copied message | Enter: send sequence | s: save",
        "In editor: Ctrl+T changes format | Enter accepts | Esc cancels",
        "Separate complete messages with semicolons; sending does not save",
        "",
        "Help: [ / ] scroll | Esc / d back");

    @Override
    public void render(Rect area, Buffer buffer) {
        if (area.isEmpty()) {
            return;
        }
        List<Line> rows = SHORTCUTS.stream()
            .flatMap(line -> HostPanel.wrap(line, Math.max(1, area.width() - 4), colors)
                .stream())
            .toList();
        int visible = Math.max(0, area.height() - 2);
        int offset = scroll.position(rows.size() - visible);
        WorkspaceView.panel(area, buffer,
            Line.from(ModuleTextStyle.span(" MIDI Rat | Keyboard help | Esc/d back ",
                Color.LIGHT_CYAN, colors)),
            rows.subList(offset, Math.min(rows.size(), offset + visible)), true, colors);
    }
}
