package io.midirat.ui;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import dev.tamboui.terminal.Backend;
import dev.tamboui.terminal.BackendFactory;
import dev.tamboui.terminal.Frame;
import dev.tamboui.terminal.Terminal;
import io.midirat.api.MonitorControls;
import io.midirat.config.NetworkConfig.Instance;
import io.midirat.host.Diagnostics;
import io.midirat.host.Host;

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
    private final SequenceEditor editor = new SequenceEditor();
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
                if (editor.active()) {
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
                    if (selected != null) {
                        selected.module()
                            .emitterControls()
                            .ifPresent(editor::begin);
                    }
                }
                case 'p' -> {
                    monitorAction(selected, MonitorControls::togglePause);
                }
                case 'c' -> {
                    monitorAction(selected, MonitorControls::clear);
                }
                case 'd' -> {
                    monitorAction(selected, MonitorControls::toggleDetails);
                }
                case 'y' -> {
                    monitorAction(selected, monitor -> {
                        copied = monitor.copy();
                        diagnostics.accept("Copied message to workspace paste buffer");
                    });
                }
                case 'v' -> {
                    if (selected != null
                        && !copied.isEmpty()) {
                        selected.module()
                            .emitterControls()
                            .ifPresent(emitter -> emitter.edit("message-text", List.of(copied)));
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

    private static void monitorAction(Instance instance,
        Consumer<MonitorControls> action) {
        if (instance != null) {
            instance.module()
                .monitorControls()
                .ifPresent(action);
        }
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
        if (key != 10
            && key != 13) {
            editor.accept(key, Optional.empty());
            return;
        }
        try {
            List<Instance> instances = host.instances();
            editor.accept(key, instances.isEmpty() ? Optional.empty()
                : instances.get(Math.floorMod(focused, instances.size()))
                    .module()
                    .emitterControls());
        } catch (IllegalArgumentException exception) {
            diagnostics.accept(exception.getMessage());
        }
    }

    private void render(Frame frame) {
        List<Instance> instances = host.instances();
        if (!instances.isEmpty()) {
            focused = Math.floorMod(focused, instances.size());
        }
        WorkspaceView view = new WorkspaceView(host.status(), instances, focused,
            diagnostics.recent(), diagnostics.lost(),
            new WorkspaceView.Editor(editor.active(), editor.format(), editor.text()),
            copied, colorTerminal);
        frame.renderWidget(view, frame.area());
    }
}
