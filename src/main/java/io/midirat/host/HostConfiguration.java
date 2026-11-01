package io.midirat.host;

import java.util.List;

import io.midirat.api.ModuleProvider.Role;
import io.midirat.config.ApplicationConfig;

/** Loaded configuration data, independent of terminal presentation and later file edits. */
public record HostConfiguration(ApplicationConfig application, List<String> applicationYaml,
    List<String> networkYaml, List<Provider> providers, int processingCapacity,
    int outputCapacity) {
    public HostConfiguration {
        applicationYaml = List.copyOf(applicationYaml);
        networkYaml = List.copyOf(networkYaml);
        providers = List.copyOf(providers);
    }

    public record Provider(String type, String version, int apiVersion, Role role) {
    }
}
