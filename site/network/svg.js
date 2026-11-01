export const palette = {
    input: { accent: '#34d399', background: '#102d2a', label: 'MIDI INPUT' },
    output: { accent: '#fbbf66', background: '#332719', label: 'MIDI OUTPUT' },
    bus: { accent: '#9dafff', background: '#202943', label: 'BROADCAST BUS' },
    module: { accent: '#6bd8ee', background: '#172e39', label: 'MODULE' },
};

export function escapeXml(value) {
    return String(value).replace(
        /[&<>"']/g,
        (character) =>
            ({
                '&': '&amp;',
                '<': '&lt;',
                '>': '&gt;',
                '"': '&quot;',
                "'": '&apos;',
            })[character],
    );
}

function shorten(value, width) {
    const characters = [...value];
    const count = Math.floor((width - 36) / 8);
    return characters.length <= count ? value : `${characters.slice(0, count - 1).join('')}…`;
}

export function renderSvg(model, layout) {
    const nodes = new Map(model.nodes.map((node) => [node.id, node]));
    const edges = new Map(model.edges.map((edge) => [edge.id, edge]));
    const paths = (layout.edges ?? [])
        .map((edge) => {
            const reference = edges.get(edge.id);
            return (edge.sections ?? [])
                .map((section) => {
                    const points = [
                        section.startPoint,
                        ...(section.bendPoints ?? []),
                        section.endPoint,
                    ];
                    const path = points
                        .map((point, index) => `${index ? 'L' : 'M'} ${point.x} ${point.y}`)
                        .join(' ');
                    return `<path class="connection" data-source="${reference.source}" data-target="${reference.target}" d="${path}" fill="none" stroke="#617a96" stroke-width="1.7" marker-end="url(#arrow)"/>`;
                })
                .join('');
        })
        .join('');
    const boxes = (layout.children ?? [])
        .map((position) => {
            const node = nodes.get(position.id);
            const color = palette[node.kind];
            const subtitle = node.type ?? color.label;
            return `<g class="graph-node" data-node="${node.id}" role="button" tabindex="0" aria-label="${escapeXml(`${node.name}, ${subtitle}`)}" transform="translate(${position.x},${position.y})">
<title>${escapeXml(`${node.name}\n${subtitle}`)}</title>
<rect class="node-body" width="${position.width}" height="${position.height}" rx="11" fill="${color.background}" stroke="${color.accent}" stroke-opacity=".58"/>
<rect x="0" y="15" width="3" height="${position.height - 30}" rx="1.5" fill="${color.accent}"/>
<text x="18" y="27" fill="#eef4fa" font-family="system-ui,sans-serif" font-size="14" font-weight="650">${escapeXml(shorten(node.name, position.width))}</text>
<text x="18" y="47" fill="${color.accent}" font-family="ui-monospace,Consolas,monospace" font-size="10.5">${escapeXml(subtitle)}</text>
${node.kind === 'module' ? `<text x="18" y="66" fill="#91a5b9" font-family="system-ui,sans-serif" font-size="10">${escapeXml(node.role)}</text>` : ''}
</g>`;
        })
        .join('');
    return `<svg xmlns="http://www.w3.org/2000/svg" width="${Math.ceil(layout.width)}" height="${Math.ceil(layout.height)}" viewBox="0 0 ${layout.width} ${layout.height}" role="img" aria-labelledby="graph-title graph-description">
<title id="graph-title">${escapeXml(model.name)} — MIDI network</title>
<desc id="graph-description">${escapeXml(`${model.nodes.length} nodes and ${model.edges.length} directed connections. Inputs, modules, buses, and outputs are separate nodes.`)}</desc>
<defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="#829ab5"/></marker></defs>
<rect width="100%" height="100%" fill="#0c1521"/>
<g class="connections">${paths}</g><g class="nodes">${boxes}</g></svg>`;
}
