package io.midirat.midi;

public record Envelope(Endpoint source, long receiptNanos, MidiMessage message) {
    public Envelope forward(Endpoint next, MidiMessage payload) {
        return new Envelope(next, receiptNanos, payload);
    }
}
