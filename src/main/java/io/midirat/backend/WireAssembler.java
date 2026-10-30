package io.midirat.backend;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.function.Consumer;

import io.midirat.midi.MessageType;
import io.midirat.midi.MidiMessage;

/** Adapter-side bounded framing. Realtime is emitted separately, including within SysEx. */
public final class WireAssembler {
    public static final long TIMEOUT_NANOS = Duration.ofSeconds(5)
        .toNanos();
    private final Consumer<MidiBackend.Received> receive;
    private final Consumer<String> errors;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private int status;
    private long started;
    private long receipt;

    public WireAssembler(Consumer<MidiBackend.Received> receive, Consumer<String> errors) {
        this.receive = receive;
        this.errors = errors;
    }

    public synchronized void accept(byte[] bytes) {
        accept(bytes, System.nanoTime());
    }

    public synchronized void accept(byte[] bytes, long hostReceipt) {
        long now = System.nanoTime();
        if (pending.size() > 0
            && now - started > TIMEOUT_NANOS) {
            discard("Incomplete MIDI assembly timed out");
        }
        for (byte raw : bytes) {
            int value = Byte.toUnsignedInt(raw);
            if (value >= 0xF8) {
                receive
                    .accept(new MidiBackend.Received(MidiMessage.shortMessage(value), hostReceipt));
                continue;
            }
            if (status == 0xF0
                && value == 0xF7) {
                pending.write(value);
                complete();
                continue;
            }
            if (value >= 128) {
                if (pending.size() > 0) {
                    discard("Malformed/incomplete MIDI assembly");
                }
                if (value == 0xF7) {
                    errors.accept("Unexpected SysEx end");
                    continue;
                }
                status = value;
                started = now;
                receipt = hostReceipt;
                pending.write(value);
            } else {
                if (status == 0) {
                    errors.accept("Data without status");
                    continue;
                }
                pending.write(value);
            }
            if (pending.size() > MidiMessage.MAX_BYTES - 1) {
                discard("SysEx exceeds 1048576-byte limit");
            } else if (status != 0xF0
                && pending.size() == MessageType.of(status)
                    .length()) {
                complete();
            }
        }
    }

    public synchronized void close() {
        if (pending.size() > 0) {
            discard("Incomplete MIDI assembly at disconnect");
        }
    }

    private void complete() {
        byte[] bytes = pending.toByteArray();
        pending.reset();
        status = 0;
        receive.accept(new MidiBackend.Received(new MidiMessage(bytes), receipt));
    }

    private void discard(String diagnostic) {
        pending.reset();
        status = 0;
        errors.accept(diagnostic);
    }
}
