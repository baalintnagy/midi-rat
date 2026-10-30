package io.midirat.api;

import java.util.Map;

/** Machine-readable plugin diagnostic with immutable, string-valued context attributes. */
public record ModuleError(Code code, String message, Map<String, String> context) {
    public enum Code {
        CONFIGURATION, LIFECYCLE, TRANSPORT, OVERFLOW, PROCESSING
    }

    public ModuleError {
        context = Map.copyOf(context);
    }
}
