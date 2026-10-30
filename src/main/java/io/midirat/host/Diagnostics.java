package io.midirat.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/** Logging never performs file I/O on a routing or native callback thread. */
public final class Diagnostics implements Consumer<String>, AutoCloseable {
    private final ArrayBlockingQueue<String> pending = new ArrayBlockingQueue<>(1024);
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private final LongAdder lost = new LongAdder();
    private final Thread writer;
    private volatile boolean running = true;

    public Diagnostics(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path log = directory.resolve("midi-rat.log");
        writer = Thread.ofPlatform()
            .name("diagnostic-log")
            .start(() -> {
                while (running
                    || !pending.isEmpty()) {
                    try {
                        String message = pending.poll(100, TimeUnit.MILLISECONDS);
                        if (message == null) {
                            continue;
                        }
                        synchronized (recent) {
                            if (recent.size() == 100) {
                                recent.removeFirst();
                            }
                            recent.addLast(message);
                        }
                        Files.writeString(log, "%s %s%n".formatted(Instant.now(), message),
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    } catch (InterruptedException _) {
                        Thread.currentThread()
                            .interrupt();
                        break;
                    } catch (IOException exception) {
                        synchronized (recent) {
                            if (recent.size() == 100) {
                                recent.removeFirst();
                            }
                            recent
                                .addLast("Log write failed: %s".formatted(exception.getMessage()));
                        }
                    }
                }
            });
    }

    @Override
    public void accept(String diagnostic) {
        if (!pending.offer(diagnostic == null ? "Unspecified error" : diagnostic)) {
            lost.increment();
        }
    }

    public List<String> recent() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }

    public long lost() {
        return lost.sum();
    }

    @Override
    public void close() {
        running = false;
        try {
            writer.join(2000);
        } catch (InterruptedException _) {
            Thread.currentThread()
                .interrupt();
        }
    }
}
