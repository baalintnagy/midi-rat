package io.midirat.sostenuto;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import io.midirat.api.ModuleContext;
import io.midirat.midi.Envelope;
import io.midirat.midi.MessageType;
import io.midirat.midi.MidiMessage;
import io.midirat.sostenuto.SostenutoSettings.UmbrellaTiming;

/** Ordered, bounded retrigger pulses; cancellation never invents lifecycle MIDI. */
public final class Umbrella implements AutoCloseable {
    private static final int MAX_PENDING = 4096;
    private final ModuleContext context;
    private final UmbrellaTiming timing;
    private final ScheduledThreadPoolExecutor scheduler;
    private final Set<Pending> pending = new HashSet<>();
    private final int[] depth = new int[16];
    private boolean closed;

    public Umbrella(ModuleContext context, UmbrellaTiming timing) {
        this.context = context;
        this.timing = timing;
        scheduler = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform()
                .daemon(true)
                .name("sostenuto-umbrella")
                .factory());
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    public synchronized void forward(Envelope input, List<MidiMessage> messages) {
        if (!closed
            && !messages.isEmpty()) {
            List<MidiMessage> ordered = new ArrayList<>();
            for (MidiMessage message : messages) {
                if (message.matchType() == MessageType.NOTE_OFF) {
                    ordered.addAll(cancelNote(message.channel() - 1, message.data1()));
                }
                ordered.add(message);
            }
            context.forward(input, ordered);
        }
    }

    private List<MidiMessage> cancelNote(int channel, int note) {
        List<Pending> cancelled = pending.stream()
            .filter(task -> task.channel == channel
                && !task.finish
                && task.noteOn.data1() == note)
            .toList();
        cancelled.forEach(task -> task.future.cancel(false));
        pending.removeAll(cancelled);
        depth[channel] -= cancelled.size();
        return !cancelled.isEmpty()
            && depth[channel] == 0
                ? List.of(control(channel, 0))
                : List.of();
    }

    public synchronized void protect(Envelope input, MidiMessage noteOn) {
        if (closed) {
            return;
        }
        if (pending.size() >= MAX_PENDING) {
            context.report("Umbrella queue full; retrigger passed without protection");
            context.forward(input, List.of(noteOn));
            return;
        }
        int channel = noteOn.channel() - 1;
        if (depth[channel] == 0) {
            context.forward(input, List.of(control(channel, 127)));
        }
        depth[channel]++;
        schedule(new Pending(input, noteOn, channel, false), timing.delayMs());
    }

    /** An explicit received reset may release a pulse and cancel delayed retriggers. */
    public synchronized List<MidiMessage> reset(int channel) {
        List<Pending> cancelled = pending.stream()
            .filter(task -> task.channel == channel)
            .toList();
        cancelled.forEach(task -> task.future.cancel(false));
        pending.removeAll(cancelled);
        boolean active = depth[channel] > 0;
        depth[channel] = 0;
        return active ? List.of(control(channel, 0)) : List.of();
    }

    private void schedule(Pending task, int delayMs) {
        task.future = scheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS);
        pending.add(task);
    }

    private static MidiMessage control(int channel, int value) {
        return MidiMessage.shortMessage(0xB0 | channel, 64, value);
    }

    @Override
    public synchronized void close() {
        closed = true;
        pending.forEach(task -> task.future.cancel(false));
        pending.clear();
        scheduler.shutdownNow();
    }

    private final class Pending implements Runnable {
        private final Envelope input;
        private final MidiMessage noteOn;
        private final int channel;
        private final boolean finish;
        private ScheduledFuture<?> future;

        Pending(Envelope input, MidiMessage noteOn, int channel, boolean finish) {
            this.input = input;
            this.noteOn = noteOn;
            this.channel = channel;
            this.finish = finish;
        }

        @Override
        public void run() {
            synchronized (Umbrella.this) {
                if (closed
                    || !pending.remove(this)) {
                    return;
                }
                try {
                    if (finish) {
                        depth[channel]--;
                        if (depth[channel] == 0) {
                            context.forward(input, List.of(control(channel, 0)));
                        }
                    } else {
                        context.forward(input, List.of(noteOn));
                        schedule(new Pending(input, noteOn, channel, true), timing.tailMs());
                    }
                } catch (RuntimeException exception) {
                    reset(channel);
                    context.report(
                        "Umbrella publication failed: %s".formatted(exception.getMessage()));
                }
            }
        }
    }
}
