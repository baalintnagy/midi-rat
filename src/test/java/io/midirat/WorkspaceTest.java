package io.midirat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.function.Consumer;

import dev.tamboui.backend.jline3.JLineBackend;
import io.midirat.backend.MidiBackend;
import io.midirat.config.ApplicationConfig;
import io.midirat.host.Diagnostics;
import io.midirat.host.Host;
import io.midirat.ui.Workspace;
import org.jline.terminal.Size;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkspaceTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = { "q", "jdxdhhq", "jjdmmdhhq", "host", "help" })
    void rendersTamboWorkspaceAndExitsOnKeyboard(String keys) throws Exception {
        Files.writeString(directory.resolve("application.yaml"),
            """
            schemaVersion: 1
            application: {backend: java-sound, networkFile: network.yaml}
            """
        );
        Files.copy(Path.of("config/network.yaml"), directory.resolve("network.yaml"));
        if (keys.startsWith("jj")) {
            String network = Files.readString(directory.resolve("network.yaml"))
                .replace("buses: [performance]", "buses: [performance, filtered]");
            Files.writeString(directory.resolve("network.yaml"), network);
            Files.writeString(directory.resolve("network.yaml"),
                """
                - name: filter
                  type: midi-message-filter
                  sources: [performance]
                  destinations: [filtered]
                  settings: {whitelist: [{type: NOTE}], blacklist: []}
                """
                    .indent(4),
                StandardOpenOption.APPEND);
        }
        var app = ApplicationConfig.read(directory.resolve("application.yaml"));
        MidiBackend midi = new MidiBackend() {
            @Override
            public List<Port> ports() {
                return List.of();
            }

            @Override
            public Input openInput(Port port, Consumer<Received> receive) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Output openOutput(Port port) {
                throw new UnsupportedOperationException();
            }
        };
        var bytes = new ByteArrayOutputStream();
        String inputKeys = switch (keys) {
        case "host" -> "\t\t\tdmmdq";
        case "help" -> "d]dq";
        case "q" -> keys;
        default -> "j%s".formatted(keys);
        };
        try (var diagnostics = new Diagnostics(directory.resolve("logs"));
            var host = new Host(app, midi, diagnostics, System.nanoTime());
            var terminal = TerminalBuilder.builder()
                .system(false)
                .dumb(true)
                .encoding(StandardCharsets.UTF_8)
                .streams(new ByteArrayInputStream(inputKeys.getBytes(StandardCharsets.UTF_8)),
                    bytes)
                .size(new Size(120, 40))
                .type("xterm")
                .build()) {
            new Workspace(host, diagnostics).run(new JLineBackend(terminal));
        }
        String screen = bytes.toString(StandardCharsets.UTF_8)
            .replaceAll("\u001B\\[[0-?]*[ -/]*[@-~]", "")
            .replaceAll("\\s", "");
        assertTrue(screen.contains("MIDIRat"), screen);
        assertTrue(screen.contains("manual"), screen);
        assertTrue(screen.contains("monitor"), screen);
        if (keys.equals("q")) {
            assertTrue(screen.contains(">MIDIRat"), screen);
        }
        if (keys.equals("host")) {
            assertTrue(screen.contains(">3/4"), screen);
            assertTrue(screen.contains("APPLICATIONCONFIGURATION"), screen);
            assertTrue(screen.contains("NETWORKCONFIGURATION"), screen);
        } else if (keys.equals("help")) {
            assertTrue(screen.contains("Keyboardhelp"), screen);
            assertTrue(screen.contains("Ctrl+P"), screen);
        } else if (keys.length() > 1) {
            assertTrue(screen.contains(keys.startsWith("jj") ? "BlockedLOG" : "MessagesLOG"),
                screen);
            assertTrue(screen.contains("CONNECTIONS/ACTIVITYDETAILS"), screen);
        }
    }
}
