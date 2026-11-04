package io.midirat.ui;

import java.util.ArrayList;
import java.util.List;

/** Packs adjacent compact widgets without changing their order or splitting monitors. */
final class ModuleLayout {
    static final int COLUMN_GAP = 2;

    record Item(int index, int width, int height, boolean elastic) {
    }

    record Row(List<Item> items) {
        Row {
            items = List.copyOf(items);
        }

        int width() {
            return items.stream()
                .mapToInt(Item::width)
                .sum() + COLUMN_GAP * (items.size() - 1);
        }

        int height() {
            return items.stream()
                .mapToInt(Item::height)
                .max()
                .orElse(0);
        }

        int expandedWidth(int column, int availableWidth) {
            int widest = 0;
            for (int index = 1; index < items.size(); index++) {
                if (items.get(index)
                    .width() > items.get(widest)
                        .width()) {
                    widest = index;
                }
            }
            return items.get(column)
                .width()
                + (column == widest ? Math.max(0, availableWidth - width()) : 0);
        }

        boolean elastic() {
            return items.getFirst()
                .elastic();
        }
    }

    private ModuleLayout() {
    }

    static List<Row> rows(List<Item> items, int availableWidth) {
        List<Row> rows = new ArrayList<>();
        List<Item> pending = new ArrayList<>();
        int usedWidth = 0;
        for (Item item : items) {
            int width = Math.min(item.width(), availableWidth);
            if (!pending.isEmpty()
                && (item.elastic()
                    || usedWidth + COLUMN_GAP + width > availableWidth)) {
                rows.add(new Row(pending));
                pending.clear();
                usedWidth = 0;
            }
            if (!pending.isEmpty()) {
                usedWidth += COLUMN_GAP;
            }
            pending.add(new Item(item.index(), width, item.height(), item.elastic()));
            usedWidth += width;
            if (item.elastic()) {
                rows.add(new Row(pending));
                pending.clear();
                usedWidth = 0;
            }
        }
        if (!pending.isEmpty()) {
            rows.add(new Row(pending));
        }
        return List.copyOf(rows);
    }
}
