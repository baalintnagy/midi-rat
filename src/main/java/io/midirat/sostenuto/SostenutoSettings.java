package io.midirat.sostenuto;

import java.util.List;

import io.midirat.midi.Endpoint;

public record SostenutoSettings(int activationThreshold, int hysteresis, Trigger trigger,
    List<Endpoint> controlSources, List<String> controlOrigins, UmbrellaTiming umbrella) {
    public SostenutoSettings {
        controlSources = List.copyOf(controlSources);
        controlOrigins = List.copyOf(controlOrigins);
    }

    public int releaseThreshold() {
        return Math.max(0, activationThreshold - hysteresis);
    }

    public record Trigger(boolean note, int number, boolean toggle, boolean invert) {
    }

    public record UmbrellaTiming(boolean enabled, int delayMs, int tailMs) {
    }
}
