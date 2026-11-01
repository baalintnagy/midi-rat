package io.midirat.host;

import java.util.List;

import io.midirat.midi.Endpoint;

/** Immutable display snapshots; presentation need not parse diagnostic strings. */
public record HostStatus(Devices devices, List<Endpoint> endpoints, List<Route> modules) {
    public HostStatus {
        endpoints = List.copyOf(endpoints);
        modules = List.copyOf(modules);
    }

    public record Devices(String backend, int inputs, int outputs, String latency,
        List<Connection> connections) {
        public Devices {
            connections = List.copyOf(connections);
        }
    }

    public record Connection(String alias, String port, boolean available, int queued, long lost) {
    }

    public record Route(String name, boolean active, int queued, long lost) {
    }
}
