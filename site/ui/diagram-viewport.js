import Panzoom from '../vendor/panzoom.js';
import { zoomAtPoint } from '../network/zoom.js';

export function createDiagramViewport(viewport, graph, element) {
    const state = { panzoom: null, layout: null };
    function fit() {
        if (!state.panzoom || !state.layout || !viewport.clientWidth || !viewport.clientHeight) {
            return;
        }
        const { width, height } = state.layout;
        const scale = Math.max(
            0.05,
            Math.min(
                (viewport.clientWidth - 64) / width,
                (viewport.clientHeight - 90) / height,
                1.35,
            ),
        );
        state.panzoom.zoom(scale, { animate: false, force: true });
        state.panzoom.pan(
            (viewport.clientWidth / scale - width) / 2,
            (viewport.clientHeight / scale - height) / 2,
            { animate: false, force: true },
        );
    }

    function zoomAt(scale, clientX, clientY) {
        if (!state.panzoom) {
            return;
        }
        const bounds = viewport.getBoundingClientRect();
        zoomAtPoint(state.panzoom, scale, {
            x: clientX - bounds.left - viewport.clientLeft,
            y: clientY - bounds.top - viewport.clientTop,
        });
    }
    viewport.addEventListener(
        'wheel',
        (event) => {
            if (!event.ctrlKey || !state.panzoom || event.target.closest('.zoom-tools')) {
                return;
            }
            event.preventDefault();
            const delta =
                event.deltaY *
                (event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? viewport.clientHeight : 1);
            zoomAt(
                state.panzoom.getScale() * Math.exp(-delta * 0.002),
                event.clientX,
                event.clientY,
            );
        },
        {
            passive: false,
        },
    );
    graph.addEventListener('panzoomchange', (event) => {
        element('zoom-label').textContent = `${Math.round(event.detail.scale * 100)}%`;
        element('zoom-slider').value = Math.round(event.detail.scale * 100);
    });
    element('zoom-slider').addEventListener('input', (event) => {
        const bounds = viewport.getBoundingClientRect();
        zoomAt(
            Number(event.target.value) / 100,
            bounds.left + viewport.clientLeft + viewport.clientWidth / 2,
            bounds.top + viewport.clientTop + viewport.clientHeight / 2,
        );
    });

    element('fit').addEventListener('click', fit);
    for (const [id, factor] of [
        ['zoom-in', 1.25],
        ['zoom-out', 0.8],
    ]) {
        element(id).addEventListener('click', () => {
            if (!state.panzoom) {
                return;
            }
            const bounds = viewport.getBoundingClientRect();
            zoomAt(
                state.panzoom.getScale() * factor,
                bounds.left + viewport.clientLeft + viewport.clientWidth / 2,
                bounds.top + viewport.clientTop + viewport.clientHeight / 2,
            );
        });
    }

    new ResizeObserver(() => {
        if (state.panzoom) {
            requestAnimationFrame(fit);
        }
    }).observe(viewport);

    return {
        fit,
        clear() {
            state.panzoom?.destroy();
            state.panzoom = null;
            state.layout = null;
        },
        attach(layout) {
            state.layout = layout;
            state.panzoom = Panzoom(graph, {
                canvas: true,
                origin: '0 0',
                minScale: 0.05,
                maxScale: 4,
            });
        },
    };
}
