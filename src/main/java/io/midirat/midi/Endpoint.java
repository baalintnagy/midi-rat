package io.midirat.midi;

public record Endpoint(String name, Kind kind) {
    public enum Kind {
        INPUT, OUTPUT, BUS
    }
}
