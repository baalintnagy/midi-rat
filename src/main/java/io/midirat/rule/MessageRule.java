package io.midirat.rule;

import java.util.Set;

import io.midirat.midi.Endpoint;
import io.midirat.midi.Envelope;
import io.midirat.midi.MessageType;
import io.midirat.midi.MidiMessage;

public sealed interface MessageRule {
    MessageType type();

    Scope scope();

    boolean payload(MidiMessage message);

    default boolean matches(Envelope envelope) {
        MessageType actual = envelope.message()
            .matchType();
        boolean kind = type() == actual
            || type() == MessageType.NOTE
                && (actual == MessageType.NOTE_ON
                    || actual == MessageType.NOTE_OFF);
        return kind
            && scope().matches(envelope)
            && payload(envelope.message());
    }

    record Scope(Set<Endpoint> sources, IntSelection statuses) {
        public Scope {
            sources = Set.copyOf(sources);
        }

        boolean matches(Envelope e) {
            return (sources.isEmpty()
                || sources.contains(e.source()))
                && statuses.contains(e.message()
                    .status());
        }
    }

    record Channel(IntSelection channels, IntSelection raw1, IntSelection raw2) {
        boolean matches(MidiMessage m) {
            return channels.contains(m.channel())
                && raw1.contains(m.data1())
                && raw2.contains(m.data2());
        }
    }

    record Note(MessageType type, Scope scope, Channel channel,
        IntSelection notes, IntSelection velocity) implements MessageRule {
        public Note {
            if (type != MessageType.NOTE
                && type != MessageType.NOTE_ON
                && type != MessageType.NOTE_OFF) {
                throw new IllegalArgumentException("Note rule requires a note kind");
            }
        }

        @Override
        public boolean payload(MidiMessage m) {
            return channel.matches(m)
                && notes.contains(m.data1())
                && velocity.contains(m.data2());
        }
    }

    record Control(Scope scope, Channel channel, IntSelection controllers,
        IntSelection value) implements MessageRule {
        @Override
        public MessageType type() {
            return MessageType.CC;
        }

        @Override
        public boolean payload(MidiMessage m) {
            return channel.matches(m)
                && controllers.contains(m.data1())
                && value.contains(m.data2());
        }
    }

    record Bend(Scope scope, Channel channel, IntSelection value) implements MessageRule {
        @Override
        public MessageType type() {
            return MessageType.P_BEND;
        }

        @Override
        public boolean payload(MidiMessage m) {
            return channel.matches(m)
                && value.contains(m.value14());
        }
    }

    record Pressure(Scope scope, Channel channel, IntSelection pressure) implements MessageRule {
        @Override
        public MessageType type() {
            return MessageType.CH_PR;
        }

        @Override
        public boolean payload(MidiMessage m) {
            return channel.matches(m)
                && pressure.contains(m.data1());
        }
    }

    record PolyPressure(Scope scope, Channel channel, IntSelection notes,
        IntSelection pressure) implements MessageRule {
        @Override
        public MessageType type() {
            return MessageType.POLY_AT;
        }

        @Override
        public boolean payload(MidiMessage m) {
            return channel.matches(m)
                && notes.contains(m.data1())
                && pressure.contains(m.data2());
        }
    }

    record Program(Scope scope, Channel channel, IntSelection programs) implements MessageRule {
        @Override
        public MessageType type() {
            return MessageType.PC;
        }

        @Override
        public boolean payload(MidiMessage m) {
            return channel.matches(m)
                && programs.contains(m.data1());
        }
    }

    record SystemData(MessageType type, Scope scope, IntSelection raw1,
        IntSelection raw2, IntSelection semantic) implements MessageRule {
        @Override
        public boolean payload(MidiMessage m) {
            return raw1.contains(m.data1())
                && raw2.contains(m.data2())
                && semantic.contains(type == MessageType.SONG_POSITION ? m.value14() : m.data1());
        }
    }

    record SystemSignal(MessageType type, Scope scope) implements MessageRule {
        @Override
        public boolean payload(MidiMessage m) {
            return true;
        }
    }
}
