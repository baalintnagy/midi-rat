package io.midirat.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class ModuleLayoutTest {
    @Test
    void pagesPreserveSharedRowsAndReserveMonitorContentHeight() {
        var rows = ModuleLayout.rows(List.of(
            new ModuleLayout.Item(0, 24, 1, false),
            new ModuleLayout.Item(1, 24, 2, false),
            new ModuleLayout.Item(2, 24, 1, true),
            new ModuleLayout.Item(3, 24, 20, false)), 80);
        var pages = ModuleLayout.pages(rows, 12);
        assertEquals(List.of(rows.subList(0, 2), rows.subList(2, 3)), pages);
        assertEquals(2, pages.getFirst()
            .getFirst()
            .items()
            .size());
        assertEquals(3, ModuleLayout.pages(rows, 11)
            .size());
        assertEquals(List.of(), ModuleLayout.pages(List.of(), 0));
    }

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
