package io.midirat.sostenuto;

import java.util.List;

import io.midirat.api.ModuleInstance;
import io.midirat.api.ModuleProvider;
import io.midirat.config.Value;
import io.midirat.midi.Endpoint;

/** External processor entry point, discovered by the host's module loader. */
public final class SostenutoProvider implements ModuleProvider {
    @Override
    public String type() {
        return "sostenuto";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public int apiVersion() {
        return HOST_API_VERSION;
    }

    @Override
    public Role role() {
        return Role.PROCESSOR;
    }

    @Override
    public ModuleInstance prepare(Value settings, List<Endpoint> subscriptions) {
        return new SostenutoModule(SostenutoDecoder.decode(settings, subscriptions));
    }
}
