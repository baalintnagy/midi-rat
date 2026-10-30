package io.midirat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import io.midirat.backend.JavaSoundBackend;
import io.midirat.backend.MidiBackend;
import io.midirat.backend.WinMmBackend;
import io.midirat.backend.WindowsServicesBackend;
import io.midirat.config.ApplicationConfig;
import io.midirat.config.NetworkConfig;
import io.midirat.host.Diagnostics;
import io.midirat.host.Host;
import io.midirat.host.Plugins;
import io.midirat.ui.Workspace;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        long origin = System.nanoTime();
        try {
            if (Arrays.asList(args)
                .contains("--help")) {
                System.out.println("MidiRat [--config path] [--validate | --ports] | --help");
                return;
            }
            Path config = defaultConfig();
            boolean validate = false;
            boolean ports = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                case "--config" -> {
                    if (++i == args.length) {
                        throw new IllegalArgumentException("--config needs a path");
                    }
                    config = Path.of(args[i]);
                }
                case "--validate" -> validate = true;
                case "--ports" -> ports = true;
                default ->
                    throw new IllegalArgumentException("Unknown option: %s".formatted(args[i]));
                }
            }
            ApplicationConfig app = ApplicationConfig.read(config);
            Path logs = app.file()
                .getParent()
                .resolve("../logs")
                .normalize();
            try (Diagnostics diagnostics = new Diagnostics(logs)) {
                if (validate) {
                    try (Plugins plugins = new Plugins(app.modules(), diagnostics)) {
                        NetworkConfig network = NetworkConfig.read(app, plugins.providers());
                        network.instances()
                            .forEach(i -> i.module()
                                .close());
                        System.out.println("Valid: %d endpoints, %d instances".formatted(
                            network.endpoints()
                                .size(),
                            network.instances()
                                .size()));
                    }
                    return;
                }
                MidiBackend backend = switch (app.backend()) {
                case "winmm" -> new WinMmBackend(diagnostics);
                case "java-sound" -> new JavaSoundBackend(diagnostics);
                case "windows-midi-services" -> new WindowsServicesBackend(diagnostics);
                default -> throw new IllegalArgumentException("Unsupported backend");
                };
                if (ports) {
                    try (backend) {
                        backend.ports()
                            .forEach(System.out::println);
                    }
                    return;
                }
                try (Host host = new Host(app, backend, diagnostics, origin)) {
                    new Workspace(host, diagnostics).run();
                }
            }
        } catch (Exception | LinkageError exception) {
            System.err.println("MIDI Rat: %s".formatted(exception.getMessage()));
            System.exit(1);
        }
    }

    private static Path defaultConfig() throws Exception {
        Path location = Path.of(Main.class.getProtectionDomain()
            .getCodeSource()
            .getLocation()
            .toURI());
        Path directory = Files.isDirectory(location) ? location : location.getParent();
        Path packaged = directory.resolve("../config/application.yaml")
            .normalize();
        return Files.isRegularFile(packaged) ? packaged : Path.of("config/application.yaml");
    }
}
