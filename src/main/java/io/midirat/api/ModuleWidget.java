package io.midirat.api;

import java.util.List;
import java.util.function.Supplier;

public interface ModuleWidget {
    List<String> lines(int availableLines);

    default boolean elastic() {
        return false;
    }

    default boolean colors() {
        return true;
    }

    /** Preferred minimum width in terminal cells, including borders and padding. */
    default int preferredWidth() {
        return 24;
    }

    static ModuleWidget fixed(Supplier<List<String>> lines) {
        return _ -> lines.get();
    }
}
