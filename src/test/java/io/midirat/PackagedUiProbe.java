package io.midirat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import dev.tamboui.backend.jline3.JLineBackend;
import dev.tamboui.terminal.Terminal;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.midirat.chord.JavaChordRecognizer;
import org.jline.terminal.Size;
import org.jline.terminal.TerminalBuilder;

/** Runs via a temporary app-image launcher to check the bundled JVM, not the build JDK. */
public final class PackagedUiProbe {
    private PackagedUiProbe() {
    }

    public static void main(String[] args) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (org.jline.terminal.Terminal terminal = TerminalBuilder.builder()
            .system(false)
            .dumb(true)
            .paused(true)
            .streams(new ByteArrayInputStream(new byte[0]), output)
            .size(new Size(80, 24))
            .type("xterm")
            .build();
            JLineBackend backend = new JLineBackend(terminal)) {
            backend.enableRawMode();
            Terminal<JLineBackend> screen = new Terminal<>(backend);
            screen.draw(frame -> frame.renderWidget(
                Paragraph.builder()
                    .text(Text.from("MIDI Rat packaged UI probe"))
                    .build(),
                frame.area()));
        }
        String rendered = output.toString(StandardCharsets.UTF_8)
            .replaceAll("\u001B\\[[0-?]*[ -/]*[@-~]", "")
            .replaceAll("\\s", "");
        if (!rendered.contains("MIDIRatpackagedUIprobe")) {
            throw new IllegalStateException("Bundled runtime did not render the TamboUI frame");
        }
        System.out.println("Bundled runtime TamboUI smoke test passed.");
        if (!new JavaChordRecognizer().recognize(List.of(45, 48, 52, 54, 59))
            .contains("Am6/9")) {
            throw new IllegalStateException("Bundled native Java chord vocabulary did not load");
        }
        System.out.println("Bundled Java chord recognition smoke test passed.");
    }
}
