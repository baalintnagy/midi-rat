package io.midirat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import dev.tamboui.backend.jline3.JLineBackend;
import dev.tamboui.terminal.Terminal;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.midirat.chord.TonalChordRecognizer;
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
        if (!new TonalChordRecognizer().recognize(List.of(60, 64, 67))
            .contains("C")) {
            throw new IllegalStateException("Bundled runtime did not recognize a C major chord");
        }
        System.out.println("Bundled runtime Tonal chord recognition smoke test passed.");
        List<String> addedNinth = new TonalChordRecognizer().recognize(List.of(58, 60, 62, 65));
        if (!addedNinth.subList(0, 2)
            .equals(List.of("Bbadd9", "A#add9"))) {
            throw new IllegalStateException(
                "Incorrect packaged chord order: %s".formatted(addedNinth));
        }
        System.out.println("Bundled runtime Bbadd9 ordering smoke test passed.");
    }
}
