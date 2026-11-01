package io.midirat.host;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

import io.midirat.api.ModuleContext;
import io.midirat.api.SourceNames;
import io.midirat.config.NetworkConfig;
import io.midirat.config.NetworkConfig.Instance;
import io.midirat.midi.Endpoint;
import io.midirat.midi.Envelope;
import io.midirat.midi.MidiMessage;
import io.midirat.module.UnavailableModule;

public final class RoutingEngine implements AutoCloseable {
    public static final int PROCESSING_CAPACITY = 4096;

    @FunctionalInterface
    public interface Output {
        void send(Endpoint target, List<MidiMessage> messages, long receipt);
    }

    private volatile NetworkConfig config;
    private final Output output;
    private final Consumer<String> errors;
    private final long origin;
    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private volatile boolean active;
    private final List<String> originNames;

    public RoutingEngine(NetworkConfig config, Output output, Consumer<String> errors,
        long origin) {
        this(config, output, errors, origin, Map.of());
    }

    public RoutingEngine(NetworkConfig config, Output output, Consumer<String> errors,
        long origin, Map<Endpoint, String> inputNames) {
        this.config = config;
        this.output = output;
        this.errors = errors;
        this.origin = origin;
        originNames = config.endpoints()
            .values()
            .stream()
            .filter(endpoint -> endpoint.kind() != Endpoint.Kind.OUTPUT)
            .map(endpoint -> inputNames.getOrDefault(endpoint, endpoint.name()))
            .toList();
    }

    public NetworkConfig config() {
        return config;
    }

    public void prepare() {
        try {
            for (Instance instance : config.instances()) {
                Route route = new Route(instance);
                routes.put(instance.name(), route);
                route.start();
            }
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
    }

    public void activate() {
        active = true;
    }

    public void receive(Envelope envelope) {
        if (!active) {
            return;
        }
        for (Route route : routes.values()) {
            if (route.instance.sources()
                .contains(envelope.source())) {
                route.offer(envelope);
            }
        }
    }

    public void emit(String name, List<MidiMessage> messages) {
        Route route = routes.get(name);
        if (route == null
            || !route.instance.sources()
                .isEmpty()) {
            throw new IllegalArgumentException("Not an active emitter: %s".formatted(name));
        }
        long receipt = System.nanoTime() - origin;
        int submitted = 0;
        for (int i = 0; i < messages.size(); i++) {
            for (Endpoint destination : route.instance.destinations()) {
                try {
                    publish(destination, List.of(messages.get(i)), receipt);
                    submitted++;
                } catch (RuntimeException exception) {
                    throw new IllegalStateException(
                        """
                        Emitter %s partial submission: message %d/%d, destination %s, \
                        %d submissions accepted; %s\
                        """
                            .formatted(name, i + 1, messages.size(), destination.name(),
                                submitted, exception.getMessage()),
                        exception);
                }
            }
        }
    }

    private void publish(Endpoint destination, List<MidiMessage> messages, long receipt) {
        publish(destination, messages, receipt, destination, destination.name());
    }

    private void publish(Endpoint destination, List<MidiMessage> messages, long receipt,
        Endpoint messageOrigin, String originName) {
        if (destination.kind() == Endpoint.Kind.OUTPUT) {
            output.send(destination, messages, receipt);
        } else if (destination.kind() == Endpoint.Kind.BUS) {
            for (MidiMessage message : messages) {
                receive(new Envelope(destination, receipt, message, messageOrigin, originName));
            }
        } else {
            throw new IllegalArgumentException("Cannot publish to an input");
        }
    }

    public static List<MidiMessage> panicMessages() {
        List<MidiMessage> messages = new ArrayList<>();
        for (int channel = 0; channel < 16; channel++) {
            for (int cc : List.of(64, 66, 123, 120)) {
                messages.add(MidiMessage.shortMessage(0xB0 | channel, cc, 0));
            }
        }
        return List.copyOf(messages);
    }

    public void panicBuses(List<MidiMessage> messages, long receipt) {
        config.endpoints()
            .values()
            .stream()
            .filter(e -> e.kind() == Endpoint.Kind.BUS)
            .forEach(bus -> publish(bus, messages, receipt));
        errors.accept("Panic published to all internal buses (best effort; inspect queue losses)");
    }

    public List<HostStatus.Route> status() {
        return routes.values()
            .stream()
            .map(r -> new HostStatus.Route(r.instance.name(), r.running,
                r.queue.size(), r.lost.sum()))
            .toList();
    }

    public void removeType(String type) {
        routes.values()
            .stream()
            .filter(r -> r.instance.type()
                .equals(type))
            .toList()
            .forEach(r -> {
                routes.remove(r.instance.name());
                r.close();
                errors.accept("%s unavailable: plugin removed; configuration retained"
                    .formatted(r.instance.name()));
            });
        config = new NetworkConfig(config.endpoints(), config.instances()
            .stream()
            .map(i -> i.type()
                .equals(type)
                    ? new Instance(i.name(), i.type(), i.sources(), i.destinations(),
                        new UnavailableModule("Plugin removed"))
                    : i)
            .toList());
    }

    public void replace(List<Instance> replacements) {
        List<Route> candidates = new ArrayList<>();
        try {
            for (Instance instance : replacements) {
                Route candidate = new Route(instance);
                candidates.add(candidate);
                candidate.start();
            }
        } catch (RuntimeException exception) {
            candidates.forEach(Route::close);
            throw exception;
        }
        for (Route candidate : candidates) {
            Route previous = routes.put(candidate.instance.name(), candidate);
            if (previous != null) {
                previous.close();
            }
        }
        config = new NetworkConfig(config.endpoints(), config.instances()
            .stream()
            .map(old -> replacements.stream()
                .filter(i -> i.name()
                    .equals(old.name()))
                .findFirst()
                .orElse(old))
            .toList());
    }

    @Override
    public void close() {
        active = false;
        routes.values()
            .forEach(Route::close);
        routes.clear();
    }

    private final class Route implements AutoCloseable {
        private final Instance instance;
        private final ArrayBlockingQueue<Envelope> queue = new ArrayBlockingQueue<>(
            PROCESSING_CAPACITY);
        private final LongAdder lost = new LongAdder();
        private final Thread worker;
        private volatile boolean running = true;
        private volatile boolean started;

        Route(Instance instance) {
            this.instance = instance;
            worker = Thread.ofPlatform()
                .name("module-%s".formatted(instance.name()))
                .unstarted(this::run);
        }

        void start() {
            instance.module()
                .start(new ModuleContext() {
                    @Override
                    public List<Endpoint> originEndpoints() {
                        return config.endpoints()
                            .values()
                            .stream()
                            .filter(endpoint -> endpoint.kind() != Endpoint.Kind.OUTPUT)
                            .toList();
                    }

                    @Override
                    public SourceNames sourceNames() {
                        return new SourceNames(originNames,
                            instance.sources()
                                .stream()
                                .map(Endpoint::name)
                                .toList());
                    }

                    @Override
                    public void publish(Endpoint destination, List<MidiMessage> messages) {
                        if (!started
                            || !active
                            || !instance.destinations()
                                .contains(destination)) {
                            throw new IllegalStateException(
                                "Inactive instance or undeclared publication");
                        }
                        RoutingEngine.this.publish(destination, messages,
                            System.nanoTime() - origin);
                    }

                    @Override
                    public void forward(Envelope input, List<MidiMessage> messages) {
                        if (!started
                            || !running
                            || !active
                            || !instance.sources()
                                .contains(input.source())) {
                            throw new IllegalStateException(
                                "Inactive instance or undeclared source");
                        }
                        for (MidiMessage message : messages) {
                            for (Endpoint destination : instance.destinations()) {
                                RoutingEngine.this.publish(destination, List.of(message),
                                    input.receiptNanos(), input.origin(), input.originName());
                            }
                        }
                    }

                    @Override
                    public void report(String diagnostic) {
                        errors.accept("%s: %s".formatted(instance.name(), diagnostic));
                    }
                });
            started = true;
            worker.start();
        }

        void offer(Envelope envelope) {
            if (running
                && !queue.offer(envelope)) {
                lost.increment();
            }
        }

        private void run() {
            long reported = 0;
            while (running) {
                try {
                    Envelope envelope = queue.poll(100, TimeUnit.MILLISECONDS);
                    long current = lost.sum();
                    if (current != reported) {
                        errors.accept(
                            "Processing overflow %s: lost %d".formatted(instance.name(), current));
                        reported = current;
                    }
                    if (envelope == null) {
                        continue;
                    }
                    List<MidiMessage> result = instance.module()
                        .process(envelope);
                    if (!active
                        || !running
                        || !started) {
                        continue;
                    }
                    for (MidiMessage message : result) {
                        for (Endpoint destination : instance.destinations()) {
                            try {
                                publish(destination, List.of(message), envelope.receiptNanos(),
                                    envelope.origin(), envelope.originName());
                            } catch (RuntimeException exception) {
                                errors.accept(exception.getMessage());
                            }
                        }
                    }
                } catch (InterruptedException _) {
                    Thread.currentThread()
                        .interrupt();
                    break;
                } catch (RuntimeException exception) {
                    running = false;
                    errors.accept("Instance %s disabled: %s".formatted(instance.name(),
                        exception.getMessage()));
                }
            }
        }

        @Override
        public void close() {
            running = false;
            started = false;
            worker.interrupt();
            try {
                if (worker.isAlive()) {
                    worker.join(2000);
                }
            } catch (InterruptedException _) {
                Thread.currentThread()
                    .interrupt();
            }
            if (worker.isAlive()) {
                errors.accept("Non-cooperative module worker: %s".formatted(instance.name()));
                return;
            }
            queue.clear();
            FutureTask<Void> cleanup = new FutureTask<>(() -> {
                instance.module()
                    .close();
                return null;
            });
            Thread.ofPlatform()
                .daemon(true)
                .name("cleanup-%s".formatted(instance.name()))
                .start(cleanup);
            try {
                cleanup.get(2, TimeUnit.SECONDS);
            } catch (TimeoutException exception) {
                cleanup.cancel(true);
                errors.accept("Non-cooperative module cleanup: %s".formatted(instance.name()));
            } catch (InterruptedException _) {
                Thread.currentThread()
                    .interrupt();
                cleanup.cancel(true);
            } catch (ExecutionException exception) {
                errors.accept(
                    "Cleanup %s failed: %s".formatted(instance.name(), exception.getMessage()));
            }
        }
    }
}
