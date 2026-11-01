const builtins = new Map([
    ['midi-monitor', 'monitor'],
    ['midi-chord', 'monitor'],
    ['midi-emitter', 'emitter'],
    ['midi-message-filter', 'processor'],
    ['midi-remap', 'processor'],
    ['sostenuto', 'processor'],
]);

function mapping(value, document, path) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
        document.fail(path, 'Expected a mapping.');
    }
    return value;
}

function string(value, document, path) {
    if (typeof value !== 'string' || !value.trim()) {
        document.fail(path, 'Expected a nonempty string.');
    }
    return value;
}

function names(value, document, path) {
    if (value === undefined) {
        return [];
    }
    if (!Array.isArray(value)) {
        document.fail(path, 'Expected a list of endpoint names.');
    }
    const result = value.map((name, index) => string(name, document, [...path, index]));
    if (new Set(result).size !== result.length) {
        document.fail(path, 'Endpoint names must be unique.');
    }
    return result;
}

function knownKeys(value, keys, document, path) {
    for (const key of Object.keys(value)) {
        if (!keys.includes(key)) {
            document.fail([...path, key], `Unknown field: ${key}`);
        }
    }
}

export function buildNetwork(network, application = null) {
    const configuration = mapping(network.data.network, network, ['network']);
    knownKeys(network.data, ['schemaVersion', 'network'], network, []);
    knownKeys(configuration, ['buses', 'modules'], network, ['network']);
    const buses = names(configuration.buses, network, ['network', 'buses']);
    if (!Array.isArray(configuration.modules)) {
        network.fail(['network', 'modules'], 'Expected a modules list.');
    }
    const nodes = [];
    const edges = [];
    const endpoints = new Map();
    const moduleNames = new Set();
    const warnings = [];
    function endpoint(name, kind, binding = null) {
        if (endpoints.has(name)) {
            const existing = endpoints.get(name);
            if (existing.kind !== kind) {
                network.fail(
                    ['network'],
                    `Endpoint '${name}' has conflicting roles. Declare internal endpoints as buses.`,
                );
            }
            return existing;
        }
        const node = { id: `n${nodes.length}`, name, kind, binding };
        endpoints.set(name, node);
        nodes.push(node);
        return node;
    }
    if (application) {
        const app = mapping(application.data.application, application, ['application']);
        knownKeys(application.data, ['schemaVersion', 'application'], application, []);
        knownKeys(app, ['backend', 'networkFile', 'moduleDirectory', 'ports'], application, [
            'application',
        ]);
        string(app.backend, application, ['application', 'backend']);
        if (!['winmm', 'windows-midi-services', 'java-sound'].includes(app.backend)) {
            application.fail(['application', 'backend'], 'Unsupported MIDI backend.');
        }
        const ports = mapping(app.ports ?? {}, application, ['application', 'ports']);
        knownKeys(ports, ['inputs', 'outputs'], application, ['application', 'ports']);
        for (const [group, kind] of [
            ['inputs', 'input'],
            ['outputs', 'output'],
        ]) {
            const bindings = mapping(ports[group] ?? {}, application, [
                'application',
                'ports',
                group,
            ]);
            for (const [name, binding] of Object.entries(bindings)) {
                const path = ['application', 'ports', group, name];
                if (typeof binding === 'string') {
                    string(binding, application, path);
                } else {
                    mapping(binding, application, path);
                    knownKeys(binding, ['id'], application, path);
                    string(binding.id, application, [...path, 'id']);
                }
                endpoint(name, kind, binding);
            }
        }
    }
    buses.forEach((name) => endpoint(name, 'bus'));
    const modules = configuration.modules.map((value, index) => {
        const path = ['network', 'modules', index];
        const module = mapping(value, network, path);
        knownKeys(module, ['name', 'type', 'sources', 'destinations', 'settings'], network, path);
        const name = string(module.name, network, [...path, 'name']);
        const type = string(module.type, network, [...path, 'type']);
        if (moduleNames.has(name)) {
            network.fail([...path, 'name'], `Duplicate module instance: ${name}`);
        }
        moduleNames.add(name);
        const sources = names(module.sources, network, [...path, 'sources']);
        const destinations = names(module.destinations, network, [...path, 'destinations']);
        mapping(module.settings, network, [...path, 'settings']);
        const role =
            builtins.get(type) ??
            (sources.length ? (destinations.length ? 'processor' : 'monitor') : 'emitter');
        if (role === 'emitter' && module.sources !== undefined) {
            network.fail([...path, 'sources'], 'Emitters do not declare sources.');
        }
        if (role !== 'emitter' && !sources.length) {
            network.fail([...path, 'sources'], 'This module requires sources.');
        }
        if (role === 'monitor' && module.destinations !== undefined) {
            network.fail([...path, 'destinations'], 'Observers do not declare destinations.');
        }
        if (role !== 'monitor' && !destinations.length) {
            network.fail([...path, 'destinations'], 'This module requires destinations.');
        }
        if (!builtins.has(type)) {
            warnings.push(`Custom module '${type}': role inferred from its wiring.`);
        }
        const node = {
            id: `n${nodes.length}`,
            name,
            kind: 'module',
            type,
            role,
            configuration: module,
        };
        nodes.push(node);
        return { node, sources, destinations, path };
    });
    for (const { node, sources, destinations, path } of modules) {
        for (const [field, references, kind] of [
            ['sources', sources, 'input'],
            ['destinations', destinations, 'output'],
        ]) {
            for (const name of references) {
                if (application && !endpoints.has(name)) {
                    network.fail([...path, field], `Unknown endpoint: ${name}`);
                }
                const target = endpoints.get(name) ?? endpoint(name, kind);
                if (target.kind !== 'bus' && target.kind !== kind) {
                    network.fail(
                        [...path, field],
                        `Cannot use ${target.kind} '${name}' as ${field}.`,
                    );
                }
                edges.push({
                    id: `e${edges.length}`,
                    source: field === 'sources' ? target.id : node.id,
                    target: field === 'sources' ? node.id : target.id,
                });
            }
        }
    }
    if (nodes.length > 500 || edges.length > 1500) {
        network.fail(['network'], 'This viewer supports up to 500 nodes and 1500 connections.');
    }
    // A topological traversal detects the same internal routing cycles the host forbids.
    const outgoing = new Map(nodes.map((node) => [node.id, []]));
    const incoming = new Map(nodes.map((node) => [node.id, 0]));
    for (const edge of edges) {
        outgoing.get(edge.source).push(edge.target);
        incoming.set(edge.target, incoming.get(edge.target) + 1);
    }
    const ready = nodes.filter((node) => incoming.get(node.id) === 0).map((node) => node.id);
    let visited = 0;
    while (ready.length) {
        const id = ready.pop();
        visited++;
        for (const target of outgoing.get(id)) {
            incoming.set(target, incoming.get(target) - 1);
            if (incoming.get(target) === 0) {
                ready.push(target);
            }
        }
    }
    if (visited !== nodes.length) {
        network.fail(['network'], 'The network contains a routing cycle.');
    }
    if (!application) {
        warnings.push(
            'Network only: physical input/output roles inferred from references. Add its application YAML to check port aliases and bindings.',
        );
    }
    return {
        nodes,
        edges,
        warnings,
        name: network.name,
        backend: application?.data.application.backend ?? null,
    };
}
