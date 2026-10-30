package io.midirat.midi;

public record Envelope(Endpoint source, long receiptNanos, MidiMessage message, Endpoint origin,
    String originName) {
    public Envelope(Endpoint source, long receiptNanos, MidiMessage message) {
        this(source, receiptNanos, message, source);
    }

    public Envelope(Endpoint source, long receiptNanos, MidiMessage message, Endpoint origin) {
        this(source, receiptNanos, message, origin, origin.name());
    }

    public Envelope forward(Endpoint next, MidiMessage payload) {
        return new Envelope(next, receiptNanos, payload, origin, originName);
    }
}
