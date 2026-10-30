package io.midirat.sostenuto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.midirat.midi.MessageType;
import io.midirat.midi.MidiMessage;

/** Pedal emulation state, isolated from device I/O, scheduling, and presentation. */
public final class SostenutoState {
    private final SostenutoSettings settings;
    private final boolean[] sustain = new boolean[16];
    private final boolean[] sostenuto = new boolean[16];
    private final boolean[][] pressed = new boolean[16][128];
    private final boolean[][] latched = new boolean[16][128];
    private final boolean[][] deferred = new boolean[16][128];

    public SostenutoState(SostenutoSettings settings) {
        this.settings = settings;
    }

    public record Status(int sustain, int sostenuto, int pressed, int latched, int deferred) {
    }

    public Status status() {
        int sustainCount = 0;
        int sostenutoCount = 0;
        int pressedCount = 0;
        int latchedCount = 0;
        int deferredCount = 0;
        for (int channel = 0; channel < 16; channel++) {
            sustainCount += sustain[channel] ? 1 : 0;
            sostenutoCount += sostenuto[channel] ? 1 : 0;
            for (int note = 0; note < 128; note++) {
                pressedCount += pressed[channel][note] ? 1 : 0;
                latchedCount += latched[channel][note] ? 1 : 0;
                deferredCount += deferred[channel][note] ? 1 : 0;
            }
        }
        return new Status(sustainCount, sostenutoCount, pressedCount, latchedCount, deferredCount);
    }

    public boolean protects(MidiMessage message) {
        if (message.matchType() != MessageType.NOTE_ON) {
            return false;
        }
        int channel = message.channel() - 1;
        return deferred[channel][message.data1()]
            || latched[channel][message.data1()];
    }

    public List<MidiMessage> process(MidiMessage message) {
        MessageType type = message.matchType();
        int channel = message.channel() - 1;
        if (type == MessageType.NOTE_ON
            || type == MessageType.NOTE_OFF) {
            int note = message.data1();
            pressed[channel][note] = type == MessageType.NOTE_ON;
            if (type == MessageType.NOTE_OFF) {
                deferred[channel][note] = sustain[channel]
                    || (sostenuto[channel]
                        && latched[channel][note]);
                if (deferred[channel][note]) {
                    return List.of();
                }
            }
            return List.of(message);
        }
        if (type != MessageType.CC) {
            return List.of(message);
        }
        int cc = message.data1();
        if (cc == 120
            || cc == 123) {
            clear(channel, true);
            return List.of(message);
        }
        if (cc == 121) {
            sustain[channel] = false;
            sostenuto[channel] = false;
            List<MidiMessage> released = release(channel);
            clear(channel, false);
            released.add(message);
            return List.copyOf(released);
        }
        if (cc == 64) {
            sustain[channel] = message.data2() >= 64;
            return List.copyOf(release(channel));
        }
        if (cc == 66) {
            boolean wasOn = sostenuto[channel];
            boolean nowOn = wasOn ? message.data2() > settings.releaseThreshold()
                : message.data2() >= settings.activationThreshold();
            if (nowOn
                && !wasOn) {
                for (int note = 0; note < 128; note++) {
                    latched[channel][note] = pressed[channel][note]
                        || deferred[channel][note];
                }
            }
            sostenuto[channel] = nowOn;
            if (!nowOn) {
                Arrays.fill(latched[channel], false);
            }
            return List.copyOf(release(channel));
        }
        return List.of(message);
    }

    private List<MidiMessage> release(int channel) {
        List<MidiMessage> result = new ArrayList<>();
        for (int note = 0; note < 128; note++) {
            if (deferred[channel][note]
                && !pressed[channel][note]
                && !sustain[channel]
                && !(sostenuto[channel]
                    && latched[channel][note])) {
                deferred[channel][note] = false;
                result.add(MidiMessage.shortMessage(0x80 | channel, note, 0));
            }
        }
        return result;
    }

    private void clear(int channel, boolean keys) {
        if (keys) {
            Arrays.fill(pressed[channel], false);
        }
        Arrays.fill(latched[channel], false);
        Arrays.fill(deferred[channel], false);
        sustain[channel] = false;
        sostenuto[channel] = false;
    }
}
