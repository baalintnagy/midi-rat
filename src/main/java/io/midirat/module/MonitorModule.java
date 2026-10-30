package io.midirat.module;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.LongAdder;

import io.midirat.api.ModuleInstance;
import io.midirat.midi.Envelope;
import io.midirat.midi.MessageCodec;
import io.midirat.midi.MidiMessage;

public final class MonitorModule implements ModuleInstance {
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
        history.stream()
            .skip(Math.max(0, history.size() - 12))
            .forEach(e -> {
                MidiMessage m = e.message();
                String time = String.format(Locale.ROOT, "%,d", e.receiptNanos())
                    .replace(',', ' ');
                lines.add("CH: %2s | %-16s | D1: %3d | D2: %3d | Status: %3d | [%s] | %s ns"
                    .formatted(m.channel() == 0 ? "--" : m.channel(), m.type(), m.data1(),
                        m.data2(),
                        m.status(), e.source()
                            .name(),
                        time));
                if (expanded) {
                    byte[] bytes = m.bytes();
                    int previewLength = Math.min(bytes.length, 96);
                    String preview = HexFormat.ofDelimiter(" ")
                        .withUpperCase()
                        .formatHex(bytes, 0, previewLength);
                    lines.add("  length=%d | %s%s | %s".formatted(bytes.length, preview,
                        bytes.length > previewLength ? " [truncated preview]" : "",
                        MessageCodec.decoded(m)));
                }
            });
        return List.copyOf(lines);
    }
}
