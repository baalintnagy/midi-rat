package io.midirat.ui;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.text.GraphemeClusters;
import dev.tamboui.text.Line;
import io.midirat.host.HostStatus;
import io.midirat.midi.Endpoint;

/** Full-width, labeled host information with cell-aware wrapping before height allocation. */
final class HostPanel {
    /** UI-thread viewport state, clamped after each resize or details change. */
    static final class Scroll {
        private int offset;
        private int maximum;

        Scroll(int offset) {
            this.offset = Math.max(0, offset);
        }

        void move(int delta) {
            offset = Math.clamp(offset + delta, 0, maximum);
        }

        void reset() {
            offset = 0;
        }

        int position(int maximum) {
            this.maximum = Math.max(0, maximum);
            offset = Math.min(offset, this.maximum);
            return offset;
        }
    }

    private HostPanel() {
    }

    static List<Line> status(HostStatus status, boolean details, int width, boolean colors) {
        HostStatus.Devices devices = status.devices();
        List<String> rows = new ArrayList<>();
        long active = status.modules()
            .stream()
            .filter(HostStatus.Route::active)
            .count();
        int queued = status.modules()
            .stream()
            .mapToInt(HostStatus.Route::queued)
            .sum();
        long lost = status.modules()
            .stream()
            .mapToLong(HostStatus.Route::lost)
            .sum();
        rows.add(
            """
            Backend: %s | inputs=%d outputs=%d | \
            Modules: %d active, %d disabled | queue=%d lost=%d\
            """
                .formatted(devices.backend(), devices.inputs(), devices.outputs(), active,
                    status.modules()
                        .size() - active,
                    queued, lost));
        if (details) {
            rows.add("Inputs:");
            for (HostStatus.Connection input : devices.inputConnections()) {
                rows.add("    %s -> %s | %s".formatted(input.alias(), input.port(),
                    input.available() ? "open" : "unavailable"));
            }
            if (devices.inputConnections()
                .isEmpty()) {
                rows.add("    --");
            }
            rows.add("Outputs:");
            for (HostStatus.Connection connection : devices.connections()) {
                rows.add("    %s -> %s | %s | queue=%d lost=%d".formatted(
                    connection.alias(), connection.port(),
                    connection.available() ? "open" : "unavailable",
                    connection.queued(), connection.lost()));
            }
            if (devices.connections()
                .isEmpty()) {
                rows.add("    --");
            }
        }
        if (details) {
            rows.add("Modules:");
        }
        for (HostStatus.Route route : status.modules()) {
            if (details) {
                rows.add("    %s: %s | queue=%d lost=%d".formatted(route.name(),
                    route.active() ? "active" : "disabled", route.queued(), route.lost()));
            }
        }
        if (details) {
            rows.add("Buses:");
            List<Endpoint> buses = status.endpoints()
                .stream()
                .filter(endpoint -> endpoint.kind() == Endpoint.Kind.BUS)
                .toList();
            for (Endpoint bus : buses) {
                // Buses dispatch synchronously; queues and losses belong to subscribers.
                rows.add("    %s: active | queue=0 lost=0".formatted(bus.name()));
            }
            if (buses.isEmpty()) {
                rows.add("    --");
            }
            rows.add("Timing: %s".formatted(devices.latency()));
        }
        return rows.stream()
            .flatMap(row -> wrap(row, width, colors).stream())
            .toList();
    }

    static List<Line> wrap(String text, int width, boolean colors) {
        if (width <= 0) {
            return List.of();
        }
        List<Line> rows = new ArrayList<>();
        int start = 0;
        int cells = 0;
        int lastBreak = 0;
        // Paragraph has no wrapped-height query. Use TamboUI's grapheme boundaries and
        // cell widths to allocate the exact rows before rendering the styled text.
        for (int offset = 0; offset < text.length();) {
            int end = GraphemeClusters.clusterEnd(text, offset);
            int size = Line.from(text.substring(offset, end))
                .width();
            while (cells + size > width
                && offset > start) {
                int boundary = lastBreak > start ? lastBreak : offset;
                rows.add(ModuleTextStyle.render(text.substring(start, boundary), colors));
                start = boundary;
                cells = Line.from(text.substring(start, offset))
                    .width();
                lastBreak = start;
            }
            cells += size;
            if (Character.isWhitespace(text.codePointAt(offset))) {
                lastBreak = end;
            }
            offset = end;
        }
        rows.add(ModuleTextStyle.render(text.substring(start), colors));
        return List.copyOf(rows);
    }
}
