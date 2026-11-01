export function elkGraph(model, direction = 'RIGHT') {
    return {
        id: 'network',
        layoutOptions: {
            'elk.algorithm': 'layered',
            'elk.direction': direction,
            'elk.edgeRouting': 'ORTHOGONAL',
            'elk.padding': '[top=36,left=36,bottom=36,right=36]',
            'elk.spacing.nodeNode': '34',
            'elk.layered.spacing.nodeNodeBetweenLayers': '76',
            'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES',
            'elk.layered.nodePlacement.strategy': 'NETWORK_SIMPLEX',
        },
        children: model.nodes.map((node) => ({
            id: node.id,
            width: Math.min(
                360,
                Math.max(174, [...node.name].length * 8 + 44, (node.type?.length ?? 0) * 6.5 + 44),
            ),
            height: node.kind === 'module' ? 82 : 66,
            layoutOptions: { 'elk.portConstraints': 'FIXED_SIDE' },
            ports: [
                {
                    id: `${node.id}:in`,
                    width: 4,
                    height: 4,
                    layoutOptions: { 'elk.port.side': direction === 'RIGHT' ? 'WEST' : 'NORTH' },
                },
                {
                    id: `${node.id}:out`,
                    width: 4,
                    height: 4,
                    layoutOptions: { 'elk.port.side': direction === 'RIGHT' ? 'EAST' : 'SOUTH' },
                },
            ],
        })),
        edges: model.edges.map((edge) => ({
            id: edge.id,
            sources: [`${edge.source}:out`],
            targets: [`${edge.target}:in`],
        })),
    };
}

export async function layoutNetwork(model, direction = 'RIGHT', signal) {
    const graph = elkGraph(model, direction);
    const elk =
        typeof window === 'undefined'
            ? new (await import('elkjs/lib/elk.bundled.js')).default()
            : new window.ELK({
                  workerFactory: () =>
                      new Worker(new URL('../vendor/elk-worker.min.js', import.meta.url)),
              });
    let timer;
    let abort;
    try {
        const cancellation = new Promise((_, reject) => {
            abort = () => reject(new DOMException('Layout cancelled', 'AbortError'));
            signal?.addEventListener('abort', abort, { once: true });
            timer = setTimeout(
                () => reject(new Error('Layout took too long. Try a smaller network.')),
                15_000,
            );
        });
        if (signal?.aborted) {
            throw new DOMException('Layout cancelled', 'AbortError');
        }
        return await Promise.race([elk.layout(graph), cancellation]);
    } finally {
        clearTimeout(timer);
        signal?.removeEventListener('abort', abort);
        // Node uses ELK's inline test fallback; only browser instances own a Worker.
        if (typeof window !== 'undefined') {
            elk.terminateWorker();
        }
    }
}
