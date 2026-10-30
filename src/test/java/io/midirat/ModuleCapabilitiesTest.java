package io.midirat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.midirat.api.ModuleInstance;
import io.midirat.midi.Envelope;
import io.midirat.midi.MidiMessage;
import io.midirat.module.EmitterModule;
import io.midirat.module.MonitorModule;
import org.junit.jupiter.api.Test;

class ModuleCapabilitiesTest {
    @Test
    void legacyPluginWidgetsRetainDefaultRenderingAndHaveNoBuiltInControls() {
        ModuleInstance plugin = new ModuleInstance() {
            @Override
            public List<MidiMessage> process(Envelope envelope) {
                return List.of();
            }

            @Override
            public List<String> widgetLines() {
                return List.of("plugin widget");
            }
        };
        assertEquals(List.of("plugin widget"), plugin.widget()
            .lines(10));
        assertFalse(plugin.widget()
            .elastic());
        assertTrue(plugin.widget()
            .colors());
        assertTrue(plugin.emitterControls()
            .isEmpty());
        assertTrue(plugin.monitorControls()
            .isEmpty());
    }

    @Test
    void moduleControlsAreAvailableThroughTheApiAndKeepTheirBehavior() {
        ModuleInstance emitter = new EmitterModule("hex", List.of("F8"));
        var controls = emitter.emitterControls()
            .orElseThrow();
        controls.edit("hex", List.of("FA"));
        assertEquals(List.of(MidiMessage.shortMessage(0xFA)), controls.compile());
        assertTrue(emitter.monitorControls()
            .isEmpty());

        ModuleInstance monitor = new MonitorModule(20, false);
        assertTrue(monitor.widget()
            .elastic());
        assertFalse(monitor.widget()
            .colors());
        monitor.monitorControls()
            .orElseThrow()
            .togglePause();
        assertTrue(monitor.widget()
            .lines(5)
            .getFirst()
            .contains("PAUSED"));
        assertTrue(monitor.emitterControls()
            .isEmpty());
    }
}
