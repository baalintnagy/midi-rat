package io.midirat.backend;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Structure.FieldOrder;
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import io.midirat.midi.MessageType;
import io.midirat.midi.MidiMessage;

/** Direct WinMM binding; native callbacks only hand off bounded packets. */
public final class WinMmBackend implements MidiBackend {
    public interface Api extends StdCallLibrary {
        interface Callback extends StdCallCallback {
            void invoke(Pointer handle, int message, ULONG_PTR instance, ULONG_PTR p1,
                ULONG_PTR p2);
        }

        int midiInGetNumDevs();

        int midiOutGetNumDevs();

        int midiInGetDevCapsW(ULONG_PTR id, InputCaps caps, int size);

        int midiOutGetDevCapsW(ULONG_PTR id, OutputCaps caps, int size);

        int midiInOpen(PointerByReference handle, int id, Callback callback, ULONG_PTR data,
            int flags);

        int midiOutOpen(PointerByReference handle, int id, Pointer callback, ULONG_PTR data,
            int flags);

        int midiInPrepareHeader(Pointer handle, Header header, int size);

        int midiInUnprepareHeader(Pointer handle, Header header, int size);

        int midiInAddBuffer(Pointer handle, Header header, int size);

        int midiInStart(Pointer handle);

        int midiInStop(Pointer handle);

        int midiInReset(Pointer handle);

        int midiInClose(Pointer handle);

        int midiOutPrepareHeader(Pointer handle, Header header, int size);

        int midiOutUnprepareHeader(Pointer handle, Header header, int size);

        int midiOutShortMsg(Pointer handle, int message);

        int midiOutLongMsg(Pointer handle, Header header, int size);

        int midiOutClose(Pointer handle);

        int midiInGetErrorTextW(int result, char[] text, int length);

        int midiOutGetErrorTextW(int result, char[] text, int length);
    }

    @FieldOrder({ "manufacturer", "product", "version", "name", "support" })
    public static class InputCaps extends Structure {
        public short manufacturer;
        public short product;
        public int version;
        public char[] name = new char[32];
        public int support;
    }

    @FieldOrder({ "manufacturer", "product", "version", "name", "technology", "voices",
            "notes", "channelMask", "support" })
    public static class OutputCaps extends Structure {
        public short manufacturer;
        public short product;
        public int version;
        public char[] name = new char[32];
        public short technology;
        public short voices;
        public short notes;
        public short channelMask;
        public int support;
    }

    @FieldOrder({ "data", "bufferLength", "bytesRecorded", "user", "flags", "next",
            "reserved", "offset", "reservedArray" })
    public static class Header extends Structure {
        public Pointer data;
        public int bufferLength;
        public int bytesRecorded;
        public ULONG_PTR user = new ULONG_PTR();
        public int flags;
        public Pointer next;
        public ULONG_PTR reserved = new ULONG_PTR();
        public int offset;
        public ULONG_PTR[] reservedArray = new ULONG_PTR[8];

        public Header() {
            for (int i = 0; i < reservedArray.length; i++) {
                reservedArray[i] = new ULONG_PTR();
            }
        }
    }

    private record Buffer(Memory memory, Header header) {
        static Buffer allocate(int size) {
            Memory memory = new Memory(size);
            Header header = new Header();
            header.data = memory;
            header.bufferLength = size;
            header.write();
            return new Buffer(memory, header);
        }
    }

    private record Packet(int kind, long p1, long nativeMilliseconds, long receipt) {
    }

    // Keep driver-owned memory/callbacks alive if a broken driver refuses close.
    private static final List<Object> QUARANTINED = new CopyOnWriteArrayList<>();
    private final Api api;
    private final Consumer<String> errors;

    public WinMmBackend(Consumer<String> errors) {
        if (!Platform.isWindows()) {
            throw new IllegalStateException("winmm requires Windows; select java-sound explicitly");
        }
        this.errors = errors;
        api = Native.load("winmm", Api.class);
    }

    @Override
    public List<Port> ports() {
        List<Port> ports = new ArrayList<>();
        for (int i = 0; i < api.midiInGetNumDevs(); i++) {
            InputCaps caps = new InputCaps();
            check(api.midiInGetDevCapsW(new ULONG_PTR(i), caps, caps.size()), true,
                "enumerate input");
            ports.add(new Port(Integer.toString(i), Native.toString(caps.name), true, false,
                "WinMM MIDI 1.0 + SysEx; no persistent ID; driver-dependent sharing"));
        }
        for (int i = 0; i < api.midiOutGetNumDevs(); i++) {
            OutputCaps caps = new OutputCaps();
            check(api.midiOutGetDevCapsW(new ULONG_PTR(i), caps, caps.size()), false,
                "enumerate output");
            ports.add(new Port(Integer.toString(i), Native.toString(caps.name), false, false,
                "WinMM MIDI 1.0 + SysEx; no persistent ID; driver-dependent sharing"));
        }
        return List.copyOf(ports);
    }

    private void check(int result, boolean input, String operation) {
        if (result != 0) {
            char[] text = new char[256];
            if (input) {
                api.midiInGetErrorTextW(result, text, text.length);
            } else {
                api.midiOutGetErrorTextW(result, text, text.length);
            }
            throw new IllegalStateException("WinMM %s failed (%d): %s; check device/access"
                .formatted(operation, result, Native.toString(text)));
        }
    }

    @Override
    public Input openInput(Port port, Consumer<Received> receive) {
        return new NativeInput(port, receive);
    }

    private final class NativeInput implements Input {
        private final ArrayBlockingQueue<Packet> packets = new ArrayBlockingQueue<>(4100);
        private final Semaphore shortSlots = new Semaphore(4096);
        private final LongAdder dropped = new LongAdder();
        private final List<Buffer> buffers = new ArrayList<>();
        private final WireAssembler assembler;
        private final Api.Callback callback;
        private final Pointer handle;
        private final Thread worker;
        private volatile boolean running = true;

        NativeInput(Port port, Consumer<Received> receive) {
            assembler = new WireAssembler(receive, errors);
            callback = (h, kind, instance, p1, p2) -> {
                if (!running
                    || (kind != 0x3C3
                        && kind != 0x3C4
                        && kind != 0x3C5
                        && kind != 0x3C6)) {
                    return;
                }
                boolean longData = kind == 0x3C4
                    || kind == 0x3C6;
                if (!longData
                    && !shortSlots.tryAcquire()) {
                    dropped.increment();
                    return;
                }
                if (!packets
                    .offer(new Packet(kind, p1.longValue(), p2.longValue(), System.nanoTime()))) {
                    if (!longData) {
                        shortSlots.release();
                    }
                    dropped.increment();
                }
            };
            PointerByReference opened = new PointerByReference();
            check(api.midiInOpen(opened, Integer.parseInt(port.key()), callback,
                new ULONG_PTR(), 0x30000), true, "open %s".formatted(port.name()));
            handle = opened.getValue();
            worker = Thread.ofPlatform()
                .name("winmm-input-%s".formatted(port.key()))
                .unstarted(this::run);
            try {
                for (int i = 0; i < 4; i++) {
                    Buffer buffer = Buffer.allocate(65_536);
                    check(api.midiInPrepareHeader(handle, buffer.header(), buffer.header()
                        .size()),
                        true, "prepare SysEx input");
                    buffers.add(buffer);
                    check(api.midiInAddBuffer(handle, buffer.header(), buffer.header()
                        .size()),
                        true, "queue SysEx input");
                }
                worker.start();
                check(api.midiInStart(handle), true, "start input");
            } catch (RuntimeException exception) {
                close();
                throw exception;
            }
        }

        private void run() {
            while (running) {
                try {
                    Packet packet = packets.poll(100, TimeUnit.MILLISECONDS);
                    long count = dropped.sumThenReset();
                    if (count > 0) {
                        errors.accept("WinMM callback queue lost %d packets".formatted(count));
                    }
                    if (packet == null) {
                        continue;
                    }
                    if (packet.kind() != 0x3C4
                        && packet.kind() != 0x3C6) {
                        shortSlots.release();
                    }
                    if (packet.kind() == 0x3C3) {
                        int status = (int) packet.p1() & 255;
                        int length = MessageType.of(status)
                            .length();
                        if (length < 1) {
                            errors.accept("Unexpected WinMM short SysEx");
                            continue;
                        }
                        byte[] bytes = new byte[length];
                        for (int i = 0; i < length; i++) {
                            bytes[i] = (byte) (packet.p1() >> (8 * i));
                        }
                        assembler.accept(bytes, packet.receipt());
                    } else if (packet.kind() == 0x3C4
                        || packet.kind() == 0x3C6) {
                        Buffer buffer = buffers.stream()
                            .filter(b -> Pointer.nativeValue(b.header()
                                .getPointer()) == packet.p1())
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException("Unknown native MIDIHDR"));
                        buffer.header()
                            .read();
                        if (packet.kind() == 0x3C6) {
                            errors.accept("WinMM malformed SysEx input");
                        } else {
                            assembler.accept(buffer.memory()
                                .getByteArray(0,
                                    buffer.header().bytesRecorded),
                                packet.receipt());
                        }
                        if (running) {
                            check(api.midiInAddBuffer(handle, buffer.header(), buffer.header()
                                .size()),
                                true, "requeue SysEx input");
                        }
                    } else {
                        errors.accept("WinMM malformed short input");
                    }
                    // p2 is unsigned milliseconds since midiInStart, not nanoseconds.
                    // Host receipt uses its own monotonic origin and never re-labels p2.
                } catch (InterruptedException _) {
                    Thread.currentThread()
                        .interrupt();
                    break;
                } catch (RuntimeException exception) {
                    errors.accept(exception.getMessage());
                }
            }
        }

        @Override
        public void close() {
            running = false;
            try {
                check(api.midiInStop(handle), true, "stop input");
                worker.interrupt();
                if (worker.isAlive()) {
                    worker.join(2000);
                }
                if (worker.isAlive()) {
                    throw new IllegalStateException("Input worker did not stop");
                }
                check(api.midiInReset(handle), true, "return input buffers");
                assembler.close();
                for (Buffer buffer : buffers) {
                    check(api.midiInUnprepareHeader(handle, buffer.header(), buffer.header()
                        .size()),
                        true, "unprepare input");
                    buffer.memory()
                        .close();
                }
                check(api.midiInClose(handle), true, "close input");
            } catch (RuntimeException | InterruptedException exception) {
                QUARANTINED.add(this);
                errors.accept("WinMM cleanup incomplete; retained native ownership: %s"
                    .formatted(exception.getMessage()));
            }
        }
    }

    @Override
    public Output openOutput(Port port) {
        PointerByReference opened = new PointerByReference();
        check(api.midiOutOpen(opened, Integer.parseInt(port.key()), null,
            new ULONG_PTR(), 0), false, "open %s".formatted(port.name()));
        Pointer handle = opened.getValue();
        return new Output() {
            private final List<Buffer> pending = new ArrayList<>();

            @Override
            public void send(MidiMessage message) {
                reap();
                if (message.type() != MessageType.SYSEX) {
                    check(api.midiOutShortMsg(handle, message.status() | message.data1() << 8
                        | message.data2() << 16), false, "send short message");
                    return;
                }
                if (pending.size() >= 16) {
                    throw new IllegalStateException("WinMM SysEx pending limit 16 reached");
                }
                Buffer buffer = Buffer.allocate(message.length());
                buffer.memory()
                    .write(0, message.bytes(), 0, message.length());
                check(api.midiOutPrepareHeader(handle, buffer.header(), buffer.header()
                    .size()),
                    false, "prepare output");
                pending.add(buffer);
                int result = api.midiOutLongMsg(handle, buffer.header(), buffer.header()
                    .size());
                if (result != 0) {
                    check(api.midiOutUnprepareHeader(handle, buffer.header(), buffer.header()
                        .size()),
                        false, "unprepare rejected SysEx");
                    pending.remove(buffer);
                    buffer.memory()
                        .close();
                    check(result, false, "send SysEx");
                }
                // Preserve wire order: do not submit a later short/long message while the
                // driver still owns this SysEx. Allow conservative DIN wire time plus margin.
                long timeout = Duration.ofSeconds(5)
                    .toNanos()
                    + message.length() * 1_000_000_000L / 3125;
                long deadline = System.nanoTime() + timeout;
                try {
                    while (!pending.isEmpty()) {
                        reap();
                        if (pending.isEmpty()) {
                            break;
                        }
                        if (System.nanoTime() >= deadline) {
                            throw new IllegalStateException(
                                "WinMM SysEx output completion timed out");
                        }
                        Thread.sleep(2);
                    }
                } catch (InterruptedException _) {
                    Thread.currentThread()
                        .interrupt();
                    throw new IllegalStateException(
                        "WinMM SysEx submission interrupted; driver ownership retained");
                }
            }

            private void reap() {
                pending.removeIf(buffer -> {
                    buffer.header()
                        .read();
                    if ((buffer.header().flags & 1) == 0) {
                        return false;
                    }
                    check(api.midiOutUnprepareHeader(handle, buffer.header(), buffer.header()
                        .size()),
                        false, "unprepare output");
                    buffer.memory()
                        .close();
                    return true;
                });
            }

            @Override
            public void close() {
                long deadline = System.nanoTime() + Duration.ofSeconds(5)
                    .toNanos();
                try {
                    while (!pending.isEmpty()
                        && System.nanoTime() < deadline) {
                        reap();
                        Thread.sleep(5);
                    }
                    if (!pending.isEmpty()) {
                        throw new IllegalStateException("Driver still owns output SysEx buffers");
                    }
                    check(api.midiOutClose(handle), false, "close output");
                } catch (RuntimeException | InterruptedException exception) {
                    QUARANTINED.add(this);
                    errors.accept("WinMM output cleanup incomplete; no implicit reset sent: %s"
                        .formatted(exception.getMessage()));
                }
            }
        };
    }
}
