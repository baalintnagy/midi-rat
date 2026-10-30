package io.midirat.host;

import java.util.Arrays;

/** Bounded rolling latency samples; measured at backend API submission, never UI render. */
public final class LatencyMetrics {
    private final long[] samples = new long[8192];
    private int count;
    private int cursor;
    private long total;
    private long maximum;

    public synchronized void record(long nanos) {
        samples[cursor] = nanos;
        cursor = (cursor + 1) % samples.length;
        count = Math.min(count + 1, samples.length);
        total++;
        maximum = Math.max(maximum, nanos);
    }

    public synchronized String summary() {
        if (count == 0) {
            return "No output latency samples";
        }
        long[] sorted = Arrays.copyOf(samples, count);
        Arrays.sort(sorted);
        long median = sorted[(count - 1) / 2];
        long p95 = sorted[(int) Math.ceil(count * 0.95) - 1];
        long p99 = sorted[(int) Math.ceil(count * 0.99) - 1];
        return "submission n=%d median=%d p95=%d p99=%d max=%d jitter(p99-median)=%d ns"
            .formatted(total, median, p95, p99, maximum, p99 - median);
    }
}
