package io.midirat.backend;

import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import io.midirat.midi.MidiMessage;

public final class WindowsServicesBackend implements MidiBackend {
    public interface Api extends Library {
        interface Receive extends Callback {
            void invoke(Pointer words, int count, long ticks);
        }

        int mr_abi_version();

        int mr_available();

        String mr_error();

        int mr_enumerate(IntByReference count);

        String mr_port_id(int index);

        String mr_port_name(int index);

        int mr_port_input(int index);

        long mr_frequency();

        int mr_open(String id, Receive callback, PointerByReference handle);

        int mr_send(Pointer handle, int[] words, int count);

        int mr_close(Pointer handle);
    }

    private record Packet(int[] words, long ticks, long receipt) {
    }

    private final Api api;
    private final Consumer<String> errors;
    private final long frequency;
    // WinRT factories may outlive a backend instance. Keep the SDK loaded for the process.
    private static NativeLibrary sdk;

    public WindowsServicesBackend(Consumer<String> errors) {
        if (!Platform.isWindows()) {
            throw new IllegalStateException("Windows MIDI Services requires Windows");
        }
        this.errors = errors;
        try {
            loadSdk();
            api = Native.load("midirat_bridge", Api.class,
                Map.of(Library.OPTION_STRING_ENCODING, "UTF-8"));
        } catch (UnsatisfiedLinkError exception) {
            throw new IllegalStateException(
                """
                Cannot load midirat_bridge.dll; install the matching packaged native \
                SDK/architecture and Windows MIDI Services; no fallback\
                """
                ,
                exception);
        }
        if (api.mr_abi_version() != 1) {
            throw new IllegalStateException("Native bridge ABI mismatch");
        }
        check(api.mr_available());
        frequency = api.mr_frequency();
        if (frequency <= 0) {
            throw new IllegalStateException("Windows MIDI Services clock unavailable");
        }
    }

    private static synchronized void loadSdk() {
        if (sdk == null) {
            // jna.library.path locates app-local DLLs; WinRT's default DLL search does not.
            // Loading this first lets WinRT activate the packaged SDK from the loaded module.
            sdk = NativeLibrary.getInstance("Windows.Devices.Midi2");
        }
    }

    private void check(int result) {
        if (result != 0) {
            throw new IllegalStateException(
                "Windows MIDI Services: %s; check installed service, SDK DLLs and architecture"
                    .formatted(api.mr_error()));
        }
    }

    @Override
    public synchronized List<Port> ports() {
        IntByReference count = new IntByReference();
        check(api.mr_enumerate(count));
        List<Port> ports = new ArrayList<>();
        for (int i = 0; i < count.getValue(); i++) {
            ports.add(new Port(api.mr_port_id(i), api.mr_port_name(i), api.mr_port_input(i) == 1,
                true, "MIDI Services endpoint/group; MIDI 1.0 + SysEx7; native MIDI 2.0 reported"));
        }
        return List.copyOf(ports);
    }

    private static int group(Port port) {
        return Integer.parseInt(port.key()
            .substring(port.key()
                .lastIndexOf("#group=") + 7));
    }

    @Override
    public Input openInput(Port port, Consumer<Received> receive) {
        return new ServiceInput(port, receive);
    }

    private final class ServiceInput implements Input {
        private final ArrayBlockingQueue<Packet> queue = new ArrayBlockingQueue<>(4096);
        private final LongAdder lost = new LongAdder();
        private final Api.Receive callback;
        private final Pointer handle;
        private final WireAssembler assembler;
        private final Thread worker;
        private volatile boolean running = true;

        ServiceInput(Port port, Consumer<Received> receive) {
            assembler = new WireAssembler(receive, errors);
            callback = (words, count, ticks) -> {
                if (!queue.offer(new Packet(count > 0
                    && count <= 4
                        ? words.getIntArray(0, count)
                        : new int[0],
                    ticks, System.nanoTime()))) {
                    lost.increment();
                }
            };
            PointerByReference opened = new PointerByReference();
            check(api.mr_open(port.key(), callback, opened));
            handle = opened.getValue();
            worker = Thread.ofPlatform()
                .name("midi-services-input")
                .start(() -> {
                    while (running) {
                        try {
                            Packet packet = queue.poll(100, TimeUnit.MILLISECONDS);
                            long dropped = lost.sumThenReset();
                            if (dropped > 0) {
                                errors.accept(
                                    "MIDI Services callback overflow: %d".formatted(dropped));
                            }
                            if (packet == null) {
                                continue;
                            }
                            if (packet.words().length == 0) {
                                errors.accept(
                                    "MIDI Services endpoint disconnected; reconnect explicitly");
                                continue;
                            }
                            // SDK ticks use MidiClock.TimestampFrequency, independent of host
                            // origin.
                            UmpCodec.ticksToNanos(packet.ticks(), frequency);
                            UmpCodec.decode(group(port), packet.words(), assembler, errors,
                                packet.receipt());
                        } catch (InterruptedException _) {
                            Thread.currentThread()
                                .interrupt();
                            break;
                        } catch (RuntimeException exception) {
                            errors.accept(exception.getMessage());
                        }
                    }
                });
        }

        @Override
        public void close() {
            try {
                check(api.mr_close(handle)); // Synchronously detaches/waits for native callbacks.
            } finally {
                running = false;
                worker.interrupt();
                try {
                    worker.join(2000);
                } catch (InterruptedException _) {
                    Thread.currentThread()
                        .interrupt();
                }
                assembler.close();
                Reference.reachabilityFence(callback);
            }
        }
    }

    @Override
    public Output openOutput(Port port) {
        PointerByReference opened = new PointerByReference();
        check(api.mr_open(port.key(), null, opened));
        Pointer handle = opened.getValue();
        return new Output() {
            @Override
            public void send(MidiMessage message) {
                for (int[] words : UmpCodec.encode(group(port), message)) {
                    check(api.mr_send(handle, words, words.length));
                }
            }

            @Override
            public void close() {
                check(api.mr_close(handle));
            }
        };
    }
}
