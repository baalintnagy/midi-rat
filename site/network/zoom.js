// Panzoom translations are in unscaled graph coordinates (transform origin 0 0).
export function zoomAtPoint(panzoom, requestedScale, point) {
    const previousScale = panzoom.getScale();
    const previousPan = panzoom.getPan();
    const result = panzoom.zoom(requestedScale, { animate: false });
    if (!result) {
        return;
    }
    // Use the actual clamped scale. The graph point under the cursor stays stationary.
    panzoom.pan(
        previousPan.x + point.x / result.scale - point.x / previousScale,
        previousPan.y + point.y / result.scale - point.y / previousScale,
        { animate: false, force: true },
    );
}
