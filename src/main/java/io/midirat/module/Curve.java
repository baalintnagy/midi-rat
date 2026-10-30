package io.midirat.module;

import java.util.List;

public record Curve(List<Point> points) {
    public record Point(int input, int output) {
    }

    public Curve {
        points = List.copyOf(points);
        if (points.size() < 2) {
            throw new IllegalArgumentException("Curve needs two points");
        }
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i)
                .input() <= points.get(i - 1)
                    .input()) {
                throw new IllegalArgumentException("Curve inputs must increase strictly");
            }
        }
    }

    public int apply(int value) {
        if (value <= points.getFirst()
            .input()) {
            return points.getFirst()
                .output();
        }
        for (int i = 1; i < points.size(); i++) {
            Point end = points.get(i);
            Point start = points.get(i - 1);
            if (value <= end.input()) {
                double fraction = (double) (value - start.input()) / (end.input() - start.input());
                return (int) Math.floor(start.output() + fraction * (end.output() - start.output())
                    + 0.5);
            }
        }
        return points.getLast()
            .output();
    }

    public void validateOutput(int max) {
        if (points.stream()
            .anyMatch(p -> p.output() < 0
                || p.output() > max)) {
            throw new IllegalArgumentException("Curve output outside 0..%d".formatted(max));
        }
    }
}
