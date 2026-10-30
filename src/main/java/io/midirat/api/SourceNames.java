package io.midirat.api;

import java.util.List;

/** Host-known presentation names, resolved before a module starts. */
public record SourceNames(List<String> origins, List<String> immediate) {
    public SourceNames {
        origins = List.copyOf(origins);
        immediate = List.copyOf(immediate);
    }

    public static SourceNames empty() {
        return new SourceNames(List.of(), List.of());
    }
}
