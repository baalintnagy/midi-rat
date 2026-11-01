import assert from 'node:assert/strict';
import test from 'node:test';
import { zoomAtPoint } from '../network/zoom.js';

function viewportTransform() {
    let scale = 1.3;
    let pan = { x: -270, y: 85 };
    return {
        getScale: () => scale,
        getPan: () => ({ ...pan }),
        zoom: (requested) => {
            scale = Math.max(0.05, Math.min(4, requested));
            return { scale };
        },
        pan: (x, y) => {
            pan = { x, y };
        },
        screen: (point) => ({ x: (point.x + pan.x) * scale, y: (point.y + pan.y) * scale }),
    };
}

for (const scales of [
    [1, 0.5, 0.1, 0.05],
    [4, 0.05, 4, 0.05, 1.3],
    [0.000001, 100, 0.001, 1.3],
]) {
    test(`zoom keeps the cursor's graph point fixed through ${scales.join(', ')}`, () => {
        const transform = viewportTransform();
        const cursor = { x: 713, y: 287 };
        const initial = transform.getPan();
        const graphPoint = {
            x: cursor.x / transform.getScale() - initial.x,
            y: cursor.y / transform.getScale() - initial.y,
        };
        for (const scale of scales) {
            zoomAtPoint(transform, scale, cursor);
            const screen = transform.screen(graphPoint);
            assert(Math.abs(screen.x - cursor.x) < 1e-8);
            assert(Math.abs(screen.y - cursor.y) < 1e-8);
        }
    });
}
