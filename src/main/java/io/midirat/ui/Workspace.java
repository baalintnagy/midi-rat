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
import io.midirat.api.ModuleDetails;
import io.midirat.api.MonitorControls;
import io.midirat.config.NetworkConfig.Instance;
import io.midirat.host.Diagnostics;
import io.midirat.host.Host;

/** UI rendering and keyboard handling stay on this thread; host commands use a control worker. */
public final class Workspace {
    private static final int LEFT = 0x110000;
    private static final int RIGHT = 0x110001;
    private final Host host;
    private final Diagnostics diagnostics;
    private final ExecutorService controls = new ThreadPoolExecutor(
        1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(16),
        Thread.ofPlatform()
            .name("host-controls")
            .factory());
    private int focused = -1;
    private final WorkspaceView.Pages pages = new WorkspaceView.Pages();
    private boolean helpDetails;
    private final HostPanel.Scroll helpScroll = new HostPanel.Scroll(0);
    private boolean hostDetails;
    private String detailsName;
    private final DetailsView.State detailState = new DetailsView.State();
    private final HostDetailsView.State hostState = new HostDetailsView.State();
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
                int key = readKey(backend);
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
                int selectedIndex = Math.floorMod(focused, instances.size() + 2);
                Instance selected = selectedIndex >= instances.size() ? null
                    : instances.get(selectedIndex);
                if (detailKey(key, instances)) {
                    continue;
                }
                switch (key) {
                case 'q', 3 -> running = false;
                case 9, 'j' -> focused++;
                case 'k' -> focused--;
                case LEFT -> focused = pages.move(-1, focused);
                case RIGHT -> focused = pages.move(1, focused);
                case 'h' -> {
                    hostDetails = true;
                    focused = instances.size();
                    hostState.reset();
                }
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
                    if (selectedIndex == instances.size() + 1) {
                        helpDetails = true;
                        helpScroll.reset();
                    } else if (selected != null) {
                        detailsName = selected.name();
                        detailState.reset();
                    } else {
                        hostDetails = true;
                        hostState.reset();
                    }
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

    private static int readKey(Backend backend) throws IOException {
        int key = backend.read(50);
        if (key != 27) {
            return key;
        }
        int prefix = backend.peek(30);
        if (prefix != '['
            && prefix != 'O') {
            return key;
        }
        backend.read(30);
        int code;
        do {
            code = backend.read(30);
        } while (code >= 0x20
            && code <= 0x3f);
        return switch (code) {
        case 'D' -> LEFT;
        case 'C' -> RIGHT;
        default -> -1;
        };
    }

    private boolean detailKey(int key, List<Instance> instances) {
        if (helpDetails) {
            switch (key) {
            case 27, 'd' -> helpDetails = false;
            case '[' -> helpScroll.move(-3);
            case ']' -> helpScroll.move(3);
            default -> {
                return key != 'q'
                    && key != 'P'
                    && key != 'r'
                    && key != 'R';
            }
            }
            return true;
        }
        if (hostDetails) {
            switch (key) {
            case 27, 'h', 'd' -> hostDetails = false;
            case 'm', 9 -> hostState.nextPage();
            case '[' -> hostState.move(-3);
            case ']' -> hostState.move(3);
            default -> {
                return key != 'q'
                    && key != 'P'
                    && key != 'r'
                    && key != 'R';
            }
            }
            return true;
        }
        if (detailsName == null) {
            return false;
        }
        Optional<Instance> selected = instances.stream()
            .filter(instance -> instance.name()
                .equals(detailsName))
            .findFirst();
        if (selected.isEmpty()) {
            detailsName = null;
            return false;
        }
        ModuleDetails details = selected.get()
            .module()
            .details();
        switch (key) {
        case 27, 'd' -> detailsName = null;
        case 'm', 9 -> {
            details.nextMode();
            detailState.reset();
        }
        case '[' -> detailState.information.move(-3);
        case ']' -> detailState.information.move(3);
        case '<' -> detailState.horizontal.move(-8);
        case '>' -> detailState.horizontal.move(8);
        case 'p' -> details.controls()
            .ifPresent(MonitorControls::togglePause);
        case 'c' -> details.controls()
            .ifPresent(MonitorControls::clear);
        case 'x' -> details.controls()
            .ifPresent(MonitorControls::toggleDetails);
        case 'y' -> details.controls()
            .ifPresent(control -> copied = control.copy());
        default -> {
            return key != 'q'
                && key != 'P'
                && key != 'r'
                && key != 'R';
        }
        }
        return true;
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
            int index = Math.floorMod(focused, instances.size() + 2);
            editor.accept(key, index >= instances.size() ? Optional.empty()
                : instances.get(index)
                    .module()
                    .emitterControls());
        } catch (IllegalArgumentException exception) {
            diagnostics.accept(exception.getMessage());
        }
    }

    private void render(Frame frame) {
        List<Instance> instances = host.instances();
        focused = Math.floorMod(focused, instances.size() + 2);
        if (helpDetails
            || hostDetails
            || detailsName != null) {
            // Keep every hidden monitor/filter capture drained while viewing details.
            instances.forEach(instance -> instance.module()
                .widget()
                .lines(1));
            if (helpDetails) {
                frame.renderWidget(new KeyboardHelpView(helpScroll, colorTerminal), frame.area());
                return;
            }
            if (hostDetails) {
                frame.renderWidget(
                    new HostDetailsView(host.status(),
                        HostConfigurationFormat.lines(host.configuration()),
                        diagnostics.recent(), diagnostics.lost(), hostState, colorTerminal),
                    frame.area());
                return;
            }
            Optional<Instance> selected = instances.stream()
                .filter(instance -> instance.name()
                    .equals(detailsName))
                .findFirst();
            if (selected.isPresent()) {
                Instance instance = selected.get();
                frame.renderWidget(new DetailsView(instance.name(), instance.type(),
                    instance.module()
                        .details(),
                    detailState, colorTerminal), frame.area());
                return;
            }
            detailsName = null;
        }
        WorkspaceView view = new WorkspaceView(host.status(), instances, focused,
            new WorkspaceView.Editor(editor.active(), editor.format(), editor.text()),
            copied, colorTerminal, pages);
        frame.renderWidget(view, frame.area());
    }
}
