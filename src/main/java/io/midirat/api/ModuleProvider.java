package io.midirat.api;

import java.util.List;

import io.midirat.config.Value;
import io.midirat.midi.Endpoint;

/** Register via META-INF/services/io.midirat.api.ModuleProvider. */
public interface ModuleProvider {
    int HOST_API_VERSION = 1;

    String type();

    String version();

    int apiVersion();

    Role role();

    ModuleInstance prepare(Value settings, List<Endpoint> subscriptions);

    enum Role {
        PROCESSOR, MONITOR, EMITTER
    }
}
