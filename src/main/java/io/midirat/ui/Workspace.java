package io.midirat.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import dev.tamboui.terminal.Backend;
import dev.tamboui.terminal.BackendFactory;
import dev.tamboui.terminal.Frame;
import dev.tamboui.terminal.Terminal;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.midirat.config.NetworkConfig.Instance;
import io.midirat.host.Diagnostics;
import io.midirat.host.Host;
import io.midirat.module.EmitterModule;
import io.midirat.module.MonitorModule;

/** UI rendering and keyboard handling stay on this thread; host commands use a control worker. */
public final class Workspace {
    private final Host host;
    private final Diagnostics diagnostics;
    private final ExecutorService controls = new ThreadPoolExecutor(
        1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(16),
        Thread.ofPlatform()
            .name("host-controls")
            .factory());
    private int focused;
    private String copied = "";
    private boolean editing;
    private final StringBuilder editor = new StringBuilder();
    private String editorFormat = "hex";
    private final boolean colorTerminal = !"dumb".equals(System.getenv("TERM"))
        && System.getenv("NO_COLOR") == null;

    public Workspace(Host host, Diagnostics diagnostics) {
        this.host = host;
        this.diagnostics = diagnostics;
    }

    public void run() throws IOException {
        run(BackendFactory.create());
    }

    public void run(Backend backend) throws IOException {
        try (backend) {
            if (backend.size()
                .width() < 1
                || backend.size()
                    .height() < 1) {
                throw new IOException(
                    """
                    No interactive terminal dimensions; use Windows Terminal or a supported \
                    console. --validate and --ports work without a TTY.\
                    """
                );
            }
            backend.enableRawMode();
            backend.enterAlternateScreen();
            backend.hideCursor();
            Terminal<Backend> terminal = new Terminal<>(backend);
            boolean running = true;
            while (running) {
                terminal.draw(this::render);
                int key = backend.read(50);
                if (key < 0) {
                    continue;
                }
                if (key == 16) {
                    command(host::panic);
                    continue;
                }
                if (key == 3) {
                    running = false;
                    continue;
                }
                if (editing) {
                    editKey(key);
                    continue;
                }
                List<Instance> instances = host.instances();
                Instance selected = instances.isEmpty() ? null
                    : instances.get(Math.floorMod(focused, instances.size()));
                switch (key) {
                case 'q', 3 -> running = false;
                case 9, 'j' -> focused++;
                case 'k' -> focused = Math.max(0, focused - 1);
                case 'P' -> command(host::panic);
                case 'r' -> command(host::reload);
                case 'R' -> command(host::reconnect);
                case 10, 13 -> {
                    if (selected != null) {
                        command(() -> host.send(selected));
                    }
                }
                case 's' -> {
                    if (selected != null) {
                        command(() -> host.save(selected));
                    }
                }
                case 'e' -> {
                    if (selected != null
                        && selected.module() instanceof EmitterModule emitter) {
                        editing = true;
                        editor.setLength(0);
                        editor.append(String.join(";", emitter.messages()));
                        editorFormat = emitter.format();
                    }
                }
                case 'p' -> {
                    if (selected != null
                        && selected.module() instanceof MonitorModule monitor) {
                        monitor.togglePause();
                    }
                }
                case 'c' -> {
                    if (selected != null
                        && selected.module() instanceof MonitorModule monitor) {
                        monitor.clear();
                    }
                }
                case 'd' -> {
                    if (selected != null
                        && selected.module() instanceof MonitorModule monitor) {
                        monitor.toggleDetails();
                    }
                }
                case 'y' -> {
                    if (selected != null
                        && selected.module() instanceof MonitorModule monitor) {
                        copied = monitor.copy();
                        diagnostics.accept("Copied message to workspace paste buffer");
                    }
                }
                case 'v' -> {
                    if (selected != null
                        && selected.module() instanceof EmitterModule emitter
                        && !copied.isEmpty()) {
                        emitter.edit("message-text", List.of(copied));
                    }
                }
                default -> {
                }
                }
            }
        } finally {
            controls.shutdown();
            try {
                if (!controls.awaitTermination(10, TimeUnit.SECONDS)) {
                    diagnostics.accept("Host control did not finish within 10 seconds");
                    controls.shutdownNow();
                }
            } catch (InterruptedException _) {
                Thread.currentThread()
                    .interrupt();
                controls.shutdownNow();
            }
        }
    }

    @FunctionalInterface
    private interface Command {
        void run() throws Exception;
    }

    private void command(Command command) {
        try {
            controls.execute(() -> {
                try {
                    command.run();
                } catch (Exception exception) {
                    diagnostics.accept(exception.getMessage());
                }
            });
        } catch (RejectedExecutionException _) {
            diagnostics.accept("Host control queue full; retry command");
        }
    }

    private void editKey(int key) {
        if (key == 27) {
            editing = false;
            return;
        }
        if (key == 20) {
            editorFormat = editorFormat.equals("hex") ? "message-text" : "hex";
            return;
        }
        if (key == 10
            || key == 13) {
            try {
                List<Instance> instances = host.instances();
                if (!instances.isEmpty()
                    && instances.get(Math.floorMod(focused, instances.size()))
                        .module() instanceof EmitterModule emitter) {
                    emitter.edit(editorFormat, editor.isEmpty() ? List.of()
                        : List.of(editor.toString()
                            .split(";", -1)));
                }
                editing = false;
            } catch (IllegalArgumentException exception) {
                diagnostics.accept(exception.getMessage());
            }
        } else if (key == 127
            || key == 8) {
            if (!editor.isEmpty()) {
                editor.deleteCharAt(editor.length() - 1);
            }
        } else if (key >= 32
            && key <= 126
            && editor.length() < 2_000_000) {
            editor.append((char) key);
        }
    }

    private void render(Frame frame) {
        List<Line> lines = new ArrayList<>();
        lines.add(
            Line.from(Span.raw("MIDI Rat  |  Tab/j/k focus  P PANIC  r reload  R reconnect  q exit")
                .bold()));
        lines.add(Line.from(
            "p/c/d/y pause/clear/detail/copy | e/v/Enter/s edit/paste/send/save | Ctrl+P Panic"));
        if (!colorTerminal) {
            lines.add(Line.from("Plain color fallback: terminal/NO_COLOR"));
        }
        host.status()
            .forEach(line -> lines.add(Line.from(line)));
        List<Instance> instances = host.instances();
        if (!instances.isEmpty()) {
            focused = Math.floorMod(focused, instances.size());
        }
        for (int i = 0; i < instances.size(); i++) {
            Instance instance = instances.get(i);
            lines.add(Line.from(Span.raw("%s %s (%s)".formatted(i == focused ? ">" : " ",
                instance.name(), instance.type()))
                .bold()));
            boolean colors = colorTerminal
                && (!(instance.module() instanceof MonitorModule m)
                    || m.colors());
            for (String row : instance.module()
                .widgetLines()) {
                lines.add(MonitorRowStyle.render(row, colors));
            }
        }
        if (editing) {
            lines.add(Line.from(
                "EDIT %s (semicolon separates messages; Ctrl+T format, Enter accept, Esc cancel)"
                    .formatted(editorFormat)));
            lines.add(Line.from(editor.toString()));
        }
        if (!copied.isEmpty()) {
            lines.add(Line.from("Copy: %s".formatted(copied)));
        }
        lines.add(Line.from("Diagnostics (log queue loss %d)".formatted(diagnostics.lost())));
        List<String> recent = diagnostics.recent();
        recent.stream()
            .skip(Math.max(0, recent.size() - 4))
            .forEach(s -> lines.add(Line.from(s)));
        // Keep status/controls and the focused widget visible on small terminals.
        int height = frame.area()
            .height();
        if (lines.size() > height
            && !instances.isEmpty()) {
            List<Line> compact = new ArrayList<>(lines.subList(0, Math.min(3, lines.size())));
            Instance selected = instances.get(focused);
            compact.add(Line.from("> %s (%s)".formatted(selected.name(), selected.type())));
            selected.module()
                .widgetLines()
                .forEach(s -> compact.add(MonitorRowStyle.render(s,
                    colorTerminal
                        && (!(selected.module() instanceof MonitorModule monitor)
                            || monitor.colors()))));
            if (editing) {
                compact.add(Line.from(editor.toString()));
            }
            recent.stream()
                .skip(Math.max(0, recent.size() - 2))
                .forEach(s -> compact.add(Line.from(s)));
            lines.clear();
            lines.addAll(compact);
        }
        frame.renderWidget(Paragraph.builder()
            .text(Text.from(lines))
            .build(), frame.area());
    }
}
