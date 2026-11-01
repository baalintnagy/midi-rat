package io.midirat.ui;

import java.util.ArrayList;
import java.util.List;

import io.midirat.config.ApplicationConfig;
import io.midirat.host.HostConfiguration;

/** Human-readable configuration explanations belong to presentation, not MIDI dispatch. */
final class HostConfigurationFormat {
    private HostConfigurationFormat() {
    }

    static List<String> lines(HostConfiguration configuration) {
        ApplicationConfig app = configuration.application();
        List<String> lines = new ArrayList<>(List.of(
            "APPLICATION CONFIGURATION",
            "Application file: %s".formatted(app.file()),
            "Network file: %s".formatted(app.network()),
            "Module directory: %s".formatted(app.modules()),
            "Port bindings select exact device names or stable backend endpoint/group IDs.",
            "Application backend or binding changes require a restart.",
            "Application YAML (loaded):"));
        lines.addAll(configuration.applicationYaml());
        lines.addAll(List.of("", "NETWORK CONFIGURATION",
            "Sources subscribe to inputs or buses; destinations fan out to outputs or buses.",
            "Settings belong to each module instance. The snapshot below is the active network.",
            "Network YAML (active):"));
        lines.addAll(configuration.networkYaml());
        lines.addAll(List.of("", "MODULE PROVIDERS",
            "External JARs are loaded from the module directory and watched for changes."));
        lines.addAll(configuration.providers()
            .stream()
            .map(provider -> "%s | version=%s | API=%d | role=%s".formatted(
                provider.type(), provider.version(), provider.apiVersion(), provider.role()))
            .toList());
        lines.addAll(List.of("", "RUNTIME CONTROLS",
            "r reloads the network; R reconnects devices and revalidates configured bindings.",
            "Routing queues: module=%d messages; physical output=%d submissions.".formatted(
                configuration.processingCapacity(), configuration.outputCapacity()),
            "Timing measures backend API submission, not physical MIDI or round-trip latency.",
            "Queue overflow is reported in lost counts; it does not change filtering rules.",
            "P / Ctrl+P explicitly sends Panic to all buses and physical outputs.",
            "Reload, module removal, and shutdown do not send automatic MIDI cleanup."));
        return List.copyOf(lines);
    }
}
