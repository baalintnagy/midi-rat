package io.midirat.module;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.LongAdder;

import io.midirat.api.ModuleContext;
import io.midirat.api.ModuleInstance;
import io.midirat.api.ModuleWidget;
import io.midirat.api.MonitorControls;
import io.midirat.api.SourceNames;
import io.midirat.midi.Envelope;
import io.midirat.midi.MessageCodec;
import io.midirat.midi.MessageType;
import io.midirat.midi.MidiMessage;
import io.midirat.presentation.MonitorFormat;

public final class MonitorModule implements ModuleInstance, MonitorControls, ModuleWidget {
    private MonitorColumns columns = MonitorColumns.from(SourceNames.empty());

    @Override
    public void start(ModuleContext context) {
        columns = MonitorColumns.from(context.sourceNames());
    }

    @Override
    public Optional<MonitorControls> monitorControls() {
        return Optional.of(this);
    }

    @Override
    public ModuleWidget widget() {
        return this;
    }

    @Override
    public boolean elastic() {
        return true;
    }

    @Override
    public List<String> lines(int availableLines) {
        return widgetLines(availableLines);
    }

    private static final List<String> NOTE_NAMES = List.of(
        "C", "(C#,Db)", "D", "(D#,Eb)", "E", "F", "(F#,Gb)", "G", "(G#,Ab)", "A", "(A#,Bb)", "B");
    private final int limit;
    private final boolean colors;
    private final ArrayBlockingQueue<Envelope> incoming;
    private final ArrayDeque<Envelope> history = new ArrayDeque<>();
    private final LongAdder lost = new LongAdder();
    private volatile boolean paused;
    private boolean expanded;

    public MonitorModule(int limit, boolean colors) {
        this.limit = limit;
        this.colors = colors;
        incoming = new ArrayBlockingQueue<>(Math.min(limit, 2048));
    }

    @Override
    public List<MidiMessage> process(Envelope e) {
        if (!paused
            && !incoming.offer(e)) {
            lost.increment();
        }
        return List.of();
    }

    public void togglePause() {
        paused = !paused;
        incoming.clear();
    }

    public void clear() {
        incoming.clear();
        history.clear();
    }

    public void toggleDetails() {
        expanded = !expanded;
    }

    public boolean colors() {
        return colors;
    }

    public String copy() {
        return history.isEmpty() ? ""
            : MessageCodec.readable(history.getLast()
                .message());
    }

    @Override
    public List<String> widgetLines() {
        return widgetLines(41);
    }

    /** Render the newest complete rows within the host's current vertical budget. */
    public List<String> widgetLines(int maxLines) {
        if (!paused) {
            Envelope e;
            while ((e = incoming.poll()) != null) {
                if (history.size() == limit) {
                    history.removeFirst();
                }
                history.addLast(e);
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add("Monitor %s | rows %d/%d | UI drops %d".formatted(
            paused ? "PAUSED" : "live", history.size(), limit, lost.sum()));
        int visibleMessages = Math.max(0, maxLines - 1) / (expanded ? 2 : 1);
        history.stream()
            .skip(Math.max(0, history.size() - visibleMessages))
            .forEach(e -> {
                MidiMessage m = e.message();
                String time = MonitorFormat.timestamp(e.receiptNanos());
                String note = m.type() == MessageType.NOTE_ON
                    || m.type() == MessageType.NOTE_OFF
                        ? NOTE_NAMES.get(m.data1() % 12)
                        : "--";
                lines.add(
                    """
                    CH: %2s | %-11s | D1: %3d | D2: %3d | \
                    Status: %3d | %s | %s | %s ns | Note: %-7s\
                    """
                        .formatted(m.channel() == 0 ? "--" : m.channel(),
                            MonitorFormat.typeLabel(m.type()), m.data1(),
                            m.data2(), m.status(), columns.origin(e.originName()),
                            columns.immediate(e.source()
                                .name()),
                            time, note));
                if (expanded) {
                    byte[] bytes = m.bytes();
                    int previewLength = Math.min(bytes.length, 96);
                    String preview = HexFormat.ofDelimiter(" ")
                        .withUpperCase()
                        .formatHex(bytes, 0, previewLength);
                    lines.add("  length=%d | %s%s | %s | via: [%s]".formatted(bytes.length, preview,
                        bytes.length > previewLength ? " [truncated preview]" : "",
                        MessageCodec.decoded(m), e.source()
                            .name()));
                }
            });
        return List.copyOf(lines);
    }
}
