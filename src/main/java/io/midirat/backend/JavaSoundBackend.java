package io.midirat.backend;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.SysexMessage;
import javax.sound.midi.Transmitter;

import io.midirat.midi.MessageType;
import io.midirat.midi.MidiMessage;

public final class JavaSoundBackend implements MidiBackend {
    private final Consumer<String> errors;
    private final Map<String, SharedDevice> opened = new HashMap<>();

    private static final class SharedDevice {
        private final MidiDevice device;
        private int references;

        SharedDevice(MidiDevice device) {
            this.device = device;
            references = 1;
        }
    }

    public JavaSoundBackend(Consumer<String> errors) {
        this.errors = errors;
    }

    @Override
    public List<Port> ports() {
        List<Port> ports = new ArrayList<>();
        MidiDevice.Info[] infos = MidiSystem.getMidiDeviceInfo();
        for (int i = 0; i < infos.length; i++) {
            try {
                MidiDevice device = MidiSystem.getMidiDevice(infos[i]);
                if (device.getMaxTransmitters() != 0) {
                    ports.add(new Port("%d:in".formatted(i), infos[i].getName(), true, false,
                        "MIDI 1.0, SysEx; Java Sound provider-dependent sharing"));
                }
                if (device.getMaxReceivers() != 0) {
                    ports.add(new Port("%d:out".formatted(i), infos[i].getName(), false, false,
                        "MIDI 1.0, SysEx; Java Sound provider-dependent sharing"));
                }
            } catch (MidiUnavailableException exception) {
                errors.accept("Enumeration: %s".formatted(exception.getMessage()));
            }
        }
        return List.copyOf(ports);
    }

    private synchronized MidiDevice acquire(Port port) throws MidiUnavailableException {
        String key = port.key()
            .split(":")[0];
        SharedDevice shared = opened.get(key);
        if (shared != null) {
            shared.references++;
            return shared.device;
        }
        MidiDevice device = MidiSystem
            .getMidiDevice(MidiSystem.getMidiDeviceInfo()[Integer.parseInt(key)]);
        device.open();
        opened.put(key, new SharedDevice(device));
        return device;
    }

    private synchronized void release(Port port) {
        String key = port.key()
            .split(":")[0];
        SharedDevice shared = opened.get(key);
        if (shared != null
            && --shared.references == 0) {
            opened.remove(key);
            shared.device.close();
        }
    }

    @Override
    public Input openInput(Port port, Consumer<Received> receive) {
        MidiDevice device = null;
        try {
            device = acquire(port);
            Transmitter transmitter = device.getTransmitter();
            WireAssembler assembler = new WireAssembler(receive, errors);
            transmitter.setReceiver(new Receiver() {
                @Override
                public void send(javax.sound.midi.MidiMessage message, long timestamp) {
                    // Device timestamps are microseconds relative to provider-specific origins.
                    // Receipt time is taken by the host callback instead of relabelling them ns.
                    assembler.accept(Arrays.copyOf(message.getMessage(), message.getLength()));
                }

                @Override
                public void close() {
                    assembler.close();
                }
            });
            return () -> {
                transmitter.close();
                assembler.close();
                release(port);
            };
        } catch (MidiUnavailableException exception) {
            if (device != null) {
                release(port);
            }
            throw new IllegalStateException("Cannot open input %s: %s; check access and connection"
                .formatted(port.name(), exception.getMessage()), exception);
        }
    }

    @Override
    public Output openOutput(Port port) {
        MidiDevice device = null;
        try {
            device = acquire(port);
            Receiver receiver = device.getReceiver();
            return new Output() {
                @Override
                public void send(MidiMessage message) {
                    try {
                        if (message.type() == MessageType.SYSEX) {
                            receiver.send(new SysexMessage(message.bytes(), message.length()), -1);
                        } else {
                            ShortMessage shortMessage = new ShortMessage();
                            shortMessage.setMessage(message.status(), message.data1(),
                                message.data2());
                            receiver.send(shortMessage, -1);
                        }
                    } catch (InvalidMidiDataException | IllegalStateException exception) {
                        throw new IllegalStateException("Output %s: %s".formatted(
                            port.name(), exception.getMessage()), exception);
                    }
                }

                @Override
                public void close() {
                    receiver.close();
                    release(port);
                }
            };
        } catch (MidiUnavailableException exception) {
            if (device != null) {
                release(port);
            }
            throw new IllegalStateException("Cannot open output %s: %s; check access and connection"
                .formatted(port.name(), exception.getMessage()), exception);
        }
    }
}
