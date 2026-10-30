package io.midirat.host;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.midirat.api.EmitterControls;
import io.midirat.api.ModuleProvider;
import io.midirat.backend.MidiBackend;
import io.midirat.config.ApplicationConfig;
import io.midirat.config.EmitterPersistence;
import io.midirat.config.NetworkConfig;
import io.midirat.config.NetworkConfig.Instance;
import io.midirat.config.Value;
import io.midirat.midi.MidiMessage;

public final class Host implements AutoCloseable {
    private final ApplicationConfig app;
    private final Diagnostics diagnostics;
    private final DeviceManager devices;
    private final long origin;
    private final WatchService watch;
    private final Thread watcher;
    private volatile RoutingEngine engine;
    private Plugins plugins;
    private byte[] persisted;
    private volatile boolean running = true;

    public Host(ApplicationConfig app, MidiBackend backend, Diagnostics diagnostics,
        long origin) throws IOException {
        this.app = app;
        this.diagnostics = diagnostics;
        this.origin = origin;
        devices = new DeviceManager(backend, app, diagnostics, origin);
        watch = app.modules()
            .getFileSystem()
            .newWatchService();
        try {
            plugins = new Plugins(app.modules(), diagnostics);
            reload();
            devices.receiver(message -> {
                RoutingEngine current = engine;
                if (current != null) {
                    current.receive(message);
                }
            });
            devices.connect();
            app.modules()
                .register(watch, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
        } catch (IOException | RuntimeException exception) {
            if (engine != null) {
                engine.close();
            }
            if (plugins != null) {
                plugins.close();
            }
            devices.close();
            watch.close();
            throw exception;
        }
        watcher = Thread.ofPlatform()
            .name("plugin-watch")
            .start(this::watchPlugins);
    }

    public synchronized void reload() throws IOException {
        ApplicationConfig current = ApplicationConfig.read(app.file());
        if (!current.equals(app)) {
            throw new IllegalArgumentException(
                "Application bindings/backend changed; restart required");
        }
        byte[] snapshot = Files.readAllBytes(app.network());
        NetworkConfig config = NetworkConfig.read(app, plugins.providers());
        if (!Arrays.equals(snapshot, Files.readAllBytes(app.network()))) {
            config.instances()
                .forEach(i -> i.module()
                    .close());
            throw new IOException("Network changed during reload; retry");
        }
        RoutingEngine candidate = new RoutingEngine(config, devices::send, diagnostics, origin,
            devices.inputNames());
        candidate.prepare();
        RoutingEngine old = engine;
        candidate.activate();
        engine = candidate;
        persisted = snapshot;
        if (old != null) {
            old.close();
        }
        diagnostics.accept("Network activated: %d instances; no automatic MIDI cleanup"
            .formatted(config.instances()
                .size()));
    }

    public synchronized void reconnect() {
        devices.reconnect();
    }

    public synchronized void panic() {
        List<MidiMessage> messages = RoutingEngine.panicMessages();
        long receipt = System.nanoTime() - origin;
        engine.panicBuses(messages, receipt);
        devices.panic(messages, receipt);
        diagnostics
            .accept("Direct output Panic queued, best effort; inspect transport diagnostics");
    }

    public synchronized List<Instance> instances() {
        return engine.config()
            .instances();
    }

    public synchronized void send(Instance instance) {
        EmitterControls emitter = instance.module()
            .emitterControls()
            .orElseThrow(() -> new IllegalArgumentException("Select an emitter"));
        engine.emit(instance.name(), emitter.compile());
    }

    public synchronized void save(Instance instance) throws IOException {
        EmitterControls emitter = instance.module()
            .emitterControls()
            .orElseThrow(() -> new IllegalArgumentException("Select an emitter"));
        persisted = EmitterPersistence.save(app.network(), persisted, instance.name(), emitter);
        diagnostics.accept("Saved emitter %s".formatted(instance.name()));
    }

    public synchronized List<String> status() {
        List<String> lines = new ArrayList<>(devices.status());
        lines.add("Endpoints: %s".formatted(engine.config()
            .endpoints()
            .values()));
        lines.addAll(engine.status());
        return List.copyOf(lines);
    }

    private void watchPlugins() {
        while (running) {
            try {
                WatchKey key = watch.poll(250, TimeUnit.MILLISECONDS);
                if (key == null) {
                    continue;
                }
                boolean changed = key.pollEvents()
                    .stream()
                    .anyMatch(e -> e.context() == null
                        || e.context()
                            .toString()
                            .endsWith(".jar"));
                key.reset();
                if (!changed) {
                    continue;
                }
                // Wait for a quiet period; installation and validation never run on dispatch.
                while ((key = watch.poll(500, TimeUnit.MILLISECONDS)) != null) {
                    key.pollEvents();
                    key.reset();
                }
                updatePlugins();
            } catch (InterruptedException _) {
                Thread.currentThread()
                    .interrupt();
                break;
            } catch (ClosedWatchServiceException _) {
                break;
            } catch (IOException | RuntimeException
                | ServiceConfigurationError exception) {
                diagnostics.accept("Plugin update rejected; previous active state retained: %s"
                    .formatted(exception.getMessage()));
            }
        }
    }

    private synchronized void updatePlugins() throws IOException {
        Plugins candidate = new Plugins(app.modules(), diagnostics, plugins);
        List<Instance> replacements = new ArrayList<>();
        boolean handedOff = false;
        try {
            if (!Arrays.equals(persisted, Files.readAllBytes(app.network()))) {
                throw new IOException(
                    "Network changed externally; reload before plugin activation");
            }
            Set<String> removed = new HashSet<>(plugins.providers()
                .keySet());
            removed.removeAll(candidate.providers()
                .keySet());
            // Prepare only plugin instances; built-in instances keep their workers and state.
            Value root = Value.read(app.network());
            Map<String, Value> top = root.mapping("schemaVersion", "network");
            Value network = Value.required(top, "network", root);
            Map<String, Value> n = network.mapping("buses", "modules");
            Set<String> builtins = Set.of("midi-message-filter", "midi-remap", "midi-monitor",
                "midi-emitter");
            for (Instance old : engine.config()
                .instances()) {
                if (builtins.contains(old.type())
                    || !candidate.changed(old.type(), plugins)
                    || removed.contains(old.type())) {
                    continue;
                }
                ModuleProvider provider = candidate.providers()
                    .get(old.type());
                if (provider == null) {
                    continue;
                }
                ModuleProvider.Role expected = old.sources()
                    .isEmpty() ? ModuleProvider.Role.EMITTER
                        : old.destinations()
                            .isEmpty() ? ModuleProvider.Role.MONITOR
                                : ModuleProvider.Role.PROCESSOR;
                if (provider.role() != expected) {
                    throw new IllegalArgumentException(
                        "Plugin role changed; explicit reload required");
                }
                Value definition = Value.required(n, "modules", network)
                    .list()
                    .stream()
                    .filter(v -> Value.required(v.mapping(), "name", v)
                        .string()
                        .equals(old.name()))
                    .findFirst()
                    .orElseThrow(
                        () -> new IllegalArgumentException("Network changed; reload explicitly"));
                Map<String, Value> fields = definition.mapping("name", "type", "sources",
                    "destinations", "settings");
                replacements
                    .add(new Instance(old.name(), old.type(), old.sources(), old.destinations(),
                        provider.prepare(Value.required(fields, "settings", definition),
                            old.sources())));
            }
            if (!Arrays.equals(persisted, Files.readAllBytes(app.network()))) {
                throw new IOException(
                    "Network changed during plugin preparation; reload explicitly");
            }
            handedOff = true;
            engine.replace(replacements);
            removed.forEach(engine::removeType);
            Plugins previous = plugins;
            plugins = candidate;
            previous.close();
            diagnostics.accept("Plugin generation activated; removed types: %s".formatted(removed));
        } catch (IOException | RuntimeException exception) {
            if (!handedOff) {
                replacements.forEach(i -> i.module()
                    .close());
            }
            candidate.close();
            throw exception;
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            watch.close();
        } catch (IOException exception) {
            diagnostics.accept(exception.getMessage());
        }
        watcher.interrupt();
        try {
            watcher.join(2000);
        } catch (InterruptedException _) {
            Thread.currentThread()
                .interrupt();
        }
        synchronized (this) {
            engine.close();
            devices.close();
            plugins.close();
        }
        diagnostics.accept("Host stopped; no implicit Panic or corrective messages");
    }
}
