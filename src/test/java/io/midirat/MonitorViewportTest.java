package io.midirat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;

import io.midirat.api.ModuleContext;
import io.midirat.api.SourceNames;
import io.midirat.midi.Endpoint;
import io.midirat.midi.Envelope;
import io.midirat.midi.MessageCodec;
import io.midirat.midi.MidiMessage;
import io.midirat.module.MonitorModule;
import org.junit.jupiter.api.Test;

class MonitorViewportTest {
    @Test
    void sourceWidthsAreFixedBeforeTrafficAndTimeGrowthDoesNotMoveColumns() {
        var monitor = new MonitorModule(20, true);
        monitor.start(new ModuleContext() {
            @Override
            public SourceNames sourceNames() {
                return new SourceNames(List.of("Long hardware port", "K"),
                    List.of("bus", "long-bus"));
            }

            @Override
            public void publish(Endpoint target, List<MidiMessage> messages) {
            }

            @Override
            public void report(String diagnostic) {
            }
        });
        var input = new Endpoint("input", Endpoint.Kind.INPUT);
        monitor.process(new Envelope(new Endpoint("bus", Endpoint.Kind.BUS), 9,
            MidiMessage.shortMessage(0x90, 60, 100), input, "K"));
        String firstRow = monitor.widgetLines()
            .get(1);
        monitor.process(new Envelope(new Endpoint("long-bus", Endpoint.Kind.BUS), 1_000_000,
            MidiMessage.shortMessage(0x80, 61, 0), input, "Long hardware port"));
        var rows = monitor.widgetLines();
        assertEquals(separators(rows.get(1)), separators(rows.get(2)));
        assertEquals(separators(firstRow), separators(rows.get(2)));
        assertEquals("Long hardware port".length() + 3,
            separators(firstRow).get(5) - separators(firstRow).get(4));
    }

    private static List<Integer> separators(String row) {
        return IntStream.range(0, row.length())
            .filter(i -> row.startsWith(" | ", i))
            .boxed()
            .toList();
    }

    @Test
    void viewportResizesWithoutLosingHistoryOrChangingCopiedMidi() {
        var monitor = new MonitorModule(2000, true);
        var source = new Endpoint("input", Endpoint.Kind.INPUT);
        for (int note = 0; note < 64; note++) {
            monitor.process(new Envelope(source, note, MidiMessage.shortMessage(0x90, note, 100)));
        }
        var tall = monitor.widgetLines(51);
        assertEquals(51, tall.size());
        assertTrue(tall.getLast()
            .contains("Note: (D#,Eb)"));
        assertEquals(MidiMessage.shortMessage(0x90, 63, 100),
            MessageCodec.parse("message-text", monitor.copy()));

        var shortView = monitor.widgetLines(6);
        assertEquals(6, shortView.size());
        assertEquals(tall.getLast(), shortView.getLast());
        assertEquals(65, monitor.widgetLines(66)
            .size());

        monitor.toggleDetails();
        var details = monitor.widgetLines(10);
        assertEquals(9, details.size());
        assertTrue(details.getLast()
            .contains("length=3"));
    }
}
