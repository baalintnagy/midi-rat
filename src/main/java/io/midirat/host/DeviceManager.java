package io.midirat.host;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

import io.midirat.backend.MidiBackend;
import io.midirat.config.ApplicationConfig;
import io.midirat.midi.Endpoint;
import io.midirat.midi.Envelope;
import io.midirat.midi.MidiMessage;

/** Owns one connection and one serialized output worker per physical endpoint. */
public final class DeviceManager implements AutoCloseable {
    public static final int OUTPUT_CAPACITY = 4096;
    private final MidiBackend backend;
    private final ApplicationConfig app;
    private final Consumer<String> errors;
    private final long origin;
    private final Map<String, MidiBackend.Input> inputs = new LinkedHashMap<>();
    private final Map<String, OutputRoute> handles = new LinkedHashMap<>();
    private final Map<Endpoint, OutputRoute> outputs = new ConcurrentHashMap<>();
    private final LatencyMetrics latency = new LatencyMetrics();
    private volatile Consumer<Envelope> receive = _ -> {
    };

    public DeviceManager(MidiBackend backend, ApplicationConfig app, Consumer<String> errors,
        long origin) {
        this.backend = backend;
        this.app = app;
        this.errors = errors;
        this.origin = origin;
    }

    public void receiver(Consumer<Envelope> next) {
        receive = next;
    }

    public Map<Endpoint, String> inputNames() {
        Map<Endpoint, String> names = new LinkedHashMap<>();
        app.inputs()
            .forEach((alias, binding) -> names.put(
                new Endpoint(alias, Endpoint.Kind.INPUT), backend.resolve(binding, true)
                    .name()));
        return Map.copyOf(names);
    }

    public synchronized void connect() {
        try {
            Map<String, List<Endpoint>> shared = new LinkedHashMap<>();
            Map<String, MidiBackend.Port> ports = new LinkedHashMap<>();
            app.inputs()
                .forEach((alias, binding) -> {
                    MidiBackend.Port port = backend.resolve(binding, true);
                    ports.put(port.key(), port);
                    shared.computeIfAbsent(port.key(), _ -> new ArrayList<>())
                        .add(new Endpoint(alias, Endpoint.Kind.INPUT));
                });
            app.outputs()
                .forEach((alias, binding) -> {
                    MidiBackend.Port port = backend.resolve(binding, false);
                    OutputRoute route = handles.computeIfAbsent(port.key(),
                        _ -> new OutputRoute(port));
                    outputs.put(new Endpoint(alias, Endpoint.Kind.OUTPUT), route);
                });
            shared.forEach((key, aliases) -> {
                MidiBackend.Port port = ports.get(key);
                inputs.put(key, backend.openInput(port, message -> {
                    long receipt = message.hostReceiptNanos() - origin;
                    for (Endpoint alias : aliases) {
                        receive.accept(new Envelope(alias, receipt, message.message(), alias,
                            port.name()));
                    }
                }));
            });
            errors.accept("Connected %d inputs and %d output handles on %s".formatted(
                inputs.size(), handles.size(), app.backend()));
        } catch (RuntimeException exception) {
            disconnect();
            throw exception;
        }
    }

    public void send(Endpoint destination, List<MidiMessage> messages, long receipt) {
        OutputRoute route = outputs.get(destination);
        if (route == null
            || !route.available) {
            throw new IllegalStateException(
                "Output unavailable: %s; use reconnect".formatted(destination.name()));
        }
        route.enqueue(destination.name(), messages, receipt);
    }

    public synchronized void panic(List<MidiMessage> messages, long receipt) {
        if (handles.isEmpty()
            && !app.outputs()
                .isEmpty()) {
            errors.accept("Panic: bound outputs unavailable");
        }
        handles.values()
            .forEach(route -> {
                try {
                    route.enqueue("direct Panic", messages, receipt);
                } catch (RuntimeException exception) {
                    errors.accept("Panic: %s".formatted(exception.getMessage()));
                }
            });
    }

    public synchronized List<String> status() {
        List<String> lines = new ArrayList<>();
        lines.add("%s | inputs=%d outputs=%d | %s".formatted(app.backend(), inputs.size(),
            handles.size(), latency.summary()));
        outputs.forEach((endpoint, route) -> lines.add("%s -> %s [%s] queue=%d lost=%d".formatted(
            endpoint.name(), route.port.name(), route.available ? "open" : "unavailable",
            route.queue.size(), route.lost.sum())));
        return List.copyOf(lines);
    }

    public synchronized void reconnect() {
        disconnect();
        connect();
    }

    public synchronized void disconnect() {
        inputs.values()
            .forEach(input -> {
                try {
                    input.close();
                } catch (RuntimeException exception) {
                    errors.accept(exception.getMessage());
                }
            });
        inputs.clear();
        handles.values()
            .forEach(OutputRoute::close);
        handles.clear();
        outputs.clear();
    }

    @Override
    public void close() {
        disconnect();
        backend.close();
    }

    private record Submission(String destination, List<MidiMessage> messages, long receipt) {
    }

    private final class OutputRoute implements AutoCloseable {
        private final MidiBackend.Port port;
        private final MidiBackend.Output output;
        private final ArrayBlockingQueue<Submission> queue = new ArrayBlockingQueue<>(
            OUTPUT_CAPACITY);
        private final LongAdder lost = new LongAdder();
        private final Thread worker;
        private volatile boolean running = true;
        private volatile boolean available = true;

        OutputRoute(MidiBackend.Port port) {
            this.port = port;
            output = backend.openOutput(port);
            worker = Thread.ofPlatform()
                .name("output-%s".formatted(port.key()))
                .start(this::run);
        }

        void enqueue(String destination, List<MidiMessage> messages, long receipt) {
            if (!available) {
                throw new IllegalStateException("Device lost: %s".formatted(port.name()));
            }
            if (!queue.offer(new Submission(destination, List.copyOf(messages), receipt))) {
                lost.add(messages.size());
                throw new IllegalStateException("Output queue overflow: %s; rejected %d messages"
                    .formatted(destination, messages.size()));
            }
        }

        private void run() {
            while (running) {
                try {
                    Submission submission = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (submission == null) {
                        continue;
                    }
                    if (!available) {
                        lost.add(submission.messages()
                            .size());
                        errors.accept("Unavailable output %s rejected %d queued messages"
                            .formatted(port.name(), submission.messages()
                                .size()));
                        continue;
                    }
                    int sent = 0;
                    try {
                        for (MidiMessage message : submission.messages()) {
                            long before = System.nanoTime() - origin;
                            output.send(message);
                            latency.record(Math.max(0, before - submission.receipt()));
                            sent++;
                        }
                    } catch (RuntimeException exception) {
                        available = false;
                        lost.add(submission.messages()
                            .size() - sent);
                        errors
                            .accept("Output %s unavailable; %s sent %d/%d: %s; reconnect explicitly"
                                .formatted(port.name(), submission.destination(), sent,
                                    submission.messages()
                                        .size(),
                                    exception.getMessage()));
                    }
                } catch (InterruptedException _) {
                    Thread.currentThread()
                        .interrupt();
                    break;
                }
            }
        }

        @Override
        public void close() {
            running = false;
            worker.interrupt();
            try {
                worker.join(2000);
            } catch (InterruptedException _) {
                Thread.currentThread()
                    .interrupt();
            }
            if (worker.isAlive()) {
                errors.accept(
                    "Output worker non-cooperative: %s; handle retained".formatted(port.name()));
                return;
            }
            if (!queue.isEmpty()) {
                errors.accept("Discarded %d queued output batches at disconnect"
                    .formatted(queue.size()));
            }
            queue.clear();
            output.close();
        }
    }
}
