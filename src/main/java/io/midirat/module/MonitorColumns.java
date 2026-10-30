package io.midirat.module;

import java.util.List;

import io.midirat.api.SourceNames;

record MonitorColumns(int originWidth, int immediateWidth) {
    static MonitorColumns from(SourceNames names) {
        return new MonitorColumns(width(names.origins()), width(names.immediate()));
    }

    String origin(String name) {
        return pad(name, originWidth);
    }

    String immediate(String name) {
        return pad(name, immediateWidth);
    }

    private static int width(List<String> names) {
        return names.stream()
            .mapToInt(String::length)
            .max()
            .orElse(0);
    }

    private static String pad(String name, int width) {
        if (width == 0) {
            return name;
        }
        String visible = name.length() <= width ? name
            : "%s…".formatted(name.substring(0, width - 1));
        String format = "%%-%ds".formatted(width);
        return format.formatted(visible);
    }
}
