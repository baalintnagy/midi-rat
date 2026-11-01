package io.midirat.api;

import java.util.List;
import java.util.Optional;

/** Optional rich details; the host owns the full-screen layout and keyboard handling. */
public interface ModuleDetails {
    ModuleWidget content();

    default String informationTitle() {
        return "Details";
    }

    default List<String> information() {
        return List.of();
    }

    default String mode() {
        return "Messages";
    }

    default void nextMode() {
    }

    default Optional<MonitorControls> controls() {
        return Optional.empty();
    }

    static ModuleDetails of(ModuleWidget content, Optional<MonitorControls> controls) {
        return new ModuleDetails() {
            @Override
            public ModuleWidget content() {
                return content;
            }

            @Override
            public Optional<MonitorControls> controls() {
                return controls;
            }
        };
    }
}
