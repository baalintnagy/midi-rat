package io.midirat.module;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import io.midirat.midi.Endpoint;
import io.midirat.midi.Envelope;
import io.midirat.midi.MessageType;
import io.midirat.midi.MidiMessage;

public sealed interface Action {
    List<MidiMessage> apply(Envelope envelope);

    record ChannelMap(List<Integer> channels) implements Action {
        public ChannelMap {
            channels = List.copyOf(channels);
        }

        @Override
        public List<MidiMessage> apply(Envelope e) {
            return channels.stream()
                .map(e.message()::withChannel)
                .toList();
        }
    }

    record NoteTarget(int note, OptionalInt bend) {
    }

    record NoteMap(Map<Integer, NoteTarget> entries, int transpose) implements Action {
        public NoteMap {
            entries = Map.copyOf(entries);
        }

        @Override
        public List<MidiMessage> apply(Envelope e) {
            MidiMessage m = e.message();
            NoteTarget target = entries.get(m.data1());
            int note = target == null ? Math.clamp((long) m.data1() + transpose, 0, 127)
                : target.note();
            MidiMessage result = m.withData(1, note);
            if (target != null
                && target.bend()
                    .isPresent()
                && m.type() == MessageType.NOTE_ON
                && m.data2() > 0) {
                int bend = target.bend()
                    .getAsInt();
                return List.of(MidiMessage.shortMessage(0xE0 | m.channel() - 1,
                    bend & 127, bend >> 7), result);
            }
            return List.of(result);
        }
    }

    enum Field {
        VELOCITY, VALUE, PRESSURE, POSITION
    }

    record ValueCurve(Field field, Curve curve) implements Action {
        @Override
        public List<MidiMessage> apply(Envelope e) {
            MidiMessage m = e.message();
            boolean wide = m.type() == MessageType.P_BEND
                || m.type() == MessageType.SONG_POSITION;
            int index = m.type() == MessageType.CH_PR ? 1 : 2;
            int value = wide ? m.value14() : index == 1 ? m.data1() : m.data2();
            return List.of(wide ? m.with14(curve.apply(value))
                : m.withData(index, curve.apply(value)));
        }
    }

    record ControllerMap(List<Integer> controllers, Curve curve) implements Action {
        public ControllerMap {
            controllers = List.copyOf(controllers);
        }

        @Override
        public List<MidiMessage> apply(Envelope e) {
            int value = curve.apply(e.message()
                .data2());
            return controllers.stream()
                .map(cc -> MidiMessage.shortMessage(
                    0xB0 | e.message()
                        .channel() - 1,
                    cc, value))
                .toList();
        }
    }

    record CcToBend(Curve curve) implements Action {
        @Override
        public List<MidiMessage> apply(Envelope e) {
            int value = curve.apply(e.message()
                .data2());
            return List.of(MidiMessage.shortMessage(0xE0 | e.message()
                .channel() - 1,
                value & 127, value >> 7));
        }
    }

    record BendToCc(List<Integer> controllers, Curve curve) implements Action {
        public BendToCc {
            controllers = List.copyOf(controllers);
        }

        @Override
        public List<MidiMessage> apply(Envelope e) {
            int value = curve.apply(e.message()
                .value14());
            return controllers.stream()
                .map(cc -> MidiMessage.shortMessage(
                    0xB0 | e.message()
                        .channel() - 1,
                    cc, value))
                .toList();
        }
    }

    record Substitute(List<MidiMessage> messages) implements Action {
        public Substitute {
            messages = List.copyOf(messages);
        }

        @Override
        public List<MidiMessage> apply(Envelope e) {
            return messages;
        }
    }

    final class CcTrigger implements Action {
        private record Key(Endpoint source, int channel, int controller) {
        }

        private final Map<Key, Boolean> active = new HashMap<>();
        private final int on;
        private final int off;
        private final int note;
        private final int velocity;
        private final int offVelocity;
        private final List<Integer> channels;
        private final boolean passOriginal;

        public CcTrigger(int on, int off, int note, int velocity, int offVelocity,
            List<Integer> channels, boolean passOriginal) {
            this.on = on;
            this.off = off;
            this.note = note;
            this.velocity = velocity;
            this.offVelocity = offVelocity;
            this.channels = List.copyOf(channels);
            this.passOriginal = passOriginal;
        }

        @Override
        public List<MidiMessage> apply(Envelope e) {
            MidiMessage m = e.message();
            Key key = new Key(e.source(), m.channel(), m.data1());
            boolean previous = active.getOrDefault(key, false);
            boolean next = previous ? m.data2() > off : m.data2() >= on;
            List<MidiMessage> result = new ArrayList<>();
            if (passOriginal) {
                result.add(m);
            }
            if (next != previous) {
                active.put(key, next);
                for (int channel : channels) {
                    result.add(MidiMessage.shortMessage((next ? 0x90 : 0x80) | channel - 1,
                        note, next ? velocity : offVelocity));
                }
            }
            return List.copyOf(result);
        }
    }
}
