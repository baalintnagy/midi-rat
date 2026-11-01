package io.midirat.sostenuto;

import java.util.Optional;

import io.midirat.midi.Envelope;
import io.midirat.midi.MessageType;
import io.midirat.midi.MidiMessage;
import io.midirat.sostenuto.SostenutoSettings.Trigger;

/** Converts the configured control into CC66, separately from pedal/note ownership. */
public final class SostenutoTrigger {
    private final SostenutoSettings settings;
    private final boolean[] pressed = new boolean[16];
    private final boolean[] toggled = new boolean[16];

    public SostenutoTrigger(SostenutoSettings settings) {
        this.settings = settings;
    }

    public Optional<MidiMessage> map(Envelope input) {
        MidiMessage message = input.message();
        Trigger trigger = settings.trigger();
        boolean dedicated = !settings.controlSources()
            .isEmpty()
            || !settings.controlOrigins()
                .isEmpty();
        boolean controlSource = !dedicated
            || settings.controlSources()
                .contains(input.source())
            || settings.controlOrigins()
                .contains(input.origin()
                    .name());
        if (!controlSource
            || !matches(message, trigger)) {
            return dedicated
                && controlSource ? Optional.empty() : Optional.of(message);
        }
        int channel = message.channel() - 1;
        int value = !trigger.note()
            && trigger.invert() ? 127 - message.data2() : message.data2();
        if (trigger.toggle()) {
            boolean wasPressed = pressed[channel];
            boolean nowPressed = isPressed(message, trigger, value, wasPressed);
            pressed[channel] = nowPressed;
            if (!nowPressed
                || wasPressed) {
                return Optional.empty();
            }
            toggled[channel] = !toggled[channel];
            value = toggled[channel] ? 127 : 0;
        } else if (trigger.note()) {
            value = message.matchType() == MessageType.NOTE_ON ? 127 : 0;
        }
        return Optional.of(MidiMessage.shortMessage(0xB0 | channel, 66, value));
    }

    private static boolean matches(MidiMessage message, Trigger trigger) {
        if (message.data1() != trigger.number()) {
            return false;
        }
        if (trigger.note()) {
            return message.type() == MessageType.NOTE_ON
                || message.type() == MessageType.NOTE_OFF;
        }
        return message.type() == MessageType.CC;
    }

    private boolean isPressed(MidiMessage message, Trigger trigger, int value, boolean wasPressed) {
        if (trigger.note()) {
            return message.matchType() == MessageType.NOTE_ON;
        }
        return wasPressed ? value > settings.releaseThreshold()
            : value >= settings.activationThreshold();
    }

    public void reset(int channel) {
        pressed[channel] = false;
        toggled[channel] = false;
    }
}
