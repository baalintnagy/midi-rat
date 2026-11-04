package io.midirat.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class ModuleLayoutTest {
    @Test
    void spareColumnsGoToWidestWidgetInsteadOfReinflatingCompactNeighbors() {
        var row = new ModuleLayout.Row(List.of(
            new ModuleLayout.Item(0, 38, 2, false),
            new ModuleLayout.Item(1, 104, 2, false)));
        assertEquals(38, row.expandedWidth(0, 160));
        assertEquals(120, row.expandedWidth(1, 160));
        assertEquals(38, row.expandedWidth(0, 144));
        assertEquals(104, row.expandedWidth(1, 144));
    }
}
