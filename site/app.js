import Panzoom from './vendor/panzoom.js';
import { stringify } from './vendor/yaml/index.js';
import { MAX_FILE_SIZE, parseYaml, selectDocuments } from './network/yaml.js';
import { buildNetwork } from './network/model.js';
import { layoutNetwork } from './network/layout.js';
import { renderSvg } from './network/svg.js';
import { examples } from './network/examples.js';

const element = (id) => document.getElementById(id);
const state = {
    files: [],
    networkName: null,
    applicationName: null,
    model: null,
    layout: null,
    svg: '',
    example: true,
    revision: 0,
    abort: null,
    panzoom: null,
};
const viewport = element('viewport');
const graph = element('graph');

function message(text) {
    element('canvas-message').textContent = text;
    element('canvas-message').hidden = !text;
}

function resetDiagram() {
    state.panzoom?.destroy();
    state.panzoom = null;
    state.model = null;
    state.svg = '';
    graph.replaceChildren();
    element('download').disabled = true;
    element('node-kind').textContent = 'INSPECT A NODE';
    element('node-name').textContent = 'The details are in the wiring.';
    element('node-description').textContent =
        'Select a module, bus, or port to see its configuration and connections.';
    element('node-connections').replaceChildren();
    element('node-settings').hidden = true;
}

function renderFiles() {
    element('file-list').replaceChildren(
        ...state.files.map((file) => {
            const item = document.createElement('li');
            const icon = document.createElement('span');
            icon.className = 'file-icon';
            icon.textContent = '{}';
            const label = document.createElement('span');
            label.className = 'file-name';
            label.textContent = file.name;
            label.title = file.name;
            const remove = document.createElement('button');
            remove.type = 'button';
            remove.textContent = '×';
            remove.setAttribute('aria-label', `Remove ${file.name}`);
            remove.addEventListener('click', () => {
                state.files = state.files.filter((current) => current.name !== file.name);
                if (state.networkName === file.name) {
                    state.networkName = null;
                }
                if (state.applicationName === file.name) {
                    state.applicationName = '';
                }
                draw();
            });
            item.append(icon, label, remove);
            return item;
        }),
    );
}

function selectors(documents) {
    const networks = documents.filter((document) => document.kind === 'network');
    const applications = documents.filter((document) => document.kind === 'application');
    const options = (select, entries, selected) => {
        select.replaceChildren(...entries.map(([value, label]) => new Option(label, value)));
        if (entries.some(([value]) => value === selected)) {
            select.value = selected;
        }
    };
    options(
        element('network-select'),
        networks.map((file) => [file.name, file.name]),
        state.networkName,
    );
    options(
        element('application-select'),
        [['', 'None — infer ports'], ...applications.map((file) => [file.name, file.name])],
        state.applicationName ?? applications[0]?.name ?? '',
    );
    element('file-selectors').hidden = documents.length === 0;
}

function showError(error) {
    const location = [
        error.source,
        error.line ? `line ${error.line}, column ${error.column}` : null,
        error.path || null,
    ]
        .filter(Boolean)
        .join(' · ');
    element('error').textContent = `${location ? `${location}\n` : ''}${error.message}`;
    element('error').hidden = false;
    viewport.setAttribute('aria-busy', 'false');
    message('Could not draw this network. Check the configuration above.');
}

function fit() {
    if (!state.panzoom || !state.layout) {
        return;
    }
    const { width, height } = state.layout;
    const scale = Math.max(
        0.05,
        Math.min((viewport.clientWidth - 64) / width, (viewport.clientHeight - 90) / height, 1.35),
    );
    state.panzoom.zoom(scale, { animate: false, force: true });
    state.panzoom.pan(
        (viewport.clientWidth / scale - width) / 2,
        (viewport.clientHeight / scale - height) / 2,
        { animate: false, force: true },
    );
}

function statistics(model) {
    const counts = ['input', 'module', 'bus', 'output'].map((kind) => [
        kind,
        model.nodes.filter((node) => node.kind === kind).length,
    ]);
    element('stats').replaceChildren(
        ...counts.map(([kind, count]) => {
            const span = document.createElement('span');
            const number = document.createElement('strong');
            number.textContent = count;
            span.append(number, ` ${kind}${count === 1 ? '' : kind === 'bus' ? 'es' : 's'}`);
            return span;
        }),
    );
    element('backend').textContent = model.backend ?? 'Ports inferred';
}

async function draw() {
    const revision = ++state.revision;
    state.abort?.abort();
    state.abort = new AbortController();
    resetDiagram();
    renderFiles();
    element('error').hidden = true;
    element('warnings').hidden = true;
    element('stats').replaceChildren();
    element('backend').textContent = '';
    element('diagram-name').textContent = 'Network';
    if (!state.files.length) {
        element('file-selectors').hidden = true;
        element('source-text').value = '';
        message('Drop a network YAML file, or load an example to get started.');
        viewport.setAttribute('aria-busy', 'false');
        return;
    }
    message('Reading configuration…');
    viewport.setAttribute('aria-busy', 'true');
    const currentFile = state.files.find((file) => file.name === state.networkName);
    if (currentFile) {
        element('source-text').value = currentFile.text;
    }
    try {
        const documents = state.files.map((file) => parseYaml(file.text, file.name));
        selectors(documents);
        const { network, application } = selectDocuments(
            documents,
            state.networkName,
            state.applicationName,
        );
        state.networkName = network.name;
        state.applicationName = application?.name ?? '';
        element('network-select').value = state.networkName;
        element('application-select').value = state.applicationName;
        element('source-text').value = network.text;
        const model = buildNetwork(network, application);
        element('diagram-name').textContent = network.name;
        statistics(model);
        element('warnings').textContent = model.warnings.join('\n');
        element('warnings').hidden = model.warnings.length === 0;
        if (!model.nodes.length) {
            message('This network has no modules or buses yet.');
            viewport.setAttribute('aria-busy', 'false');
            return;
        }
        message('Arranging the network…');
        const layout = await layoutNetwork(model, element('direction').value, state.abort.signal);
        if (revision !== state.revision) {
            return;
        }
        state.model = model;
        state.layout = layout;
        state.svg = renderSvg(model, layout);
        // All configuration text is escaped by the SVG renderer; no uploaded markup is used.
        graph.innerHTML = state.svg;
        graph.style.width = `${layout.width}px`;
        graph.style.height = `${layout.height}px`;
        state.panzoom = Panzoom(graph, {
            canvas: true,
            origin: '0 0',
            minScale: 0.05,
            maxScale: 4,
        });
        requestAnimationFrame(() =>
            requestAnimationFrame(() => {
                if (revision === state.revision) {
                    fit();
                }
            }),
        );
        message('');
        viewport.setAttribute('aria-busy', 'false');
        element('download').disabled = false;
    } catch (error) {
        if (revision !== state.revision || error.name === 'AbortError') {
            return;
        }
        showError(error);
    }
}

async function importFiles(files) {
    try {
        if (!files.length) {
            return;
        }
        if (files.length > 20) {
            throw new Error('Add no more than 20 files at once.');
        }
        const incoming = [];
        const incomingNames = new Set();
        for (const file of files) {
            if (!/\.ya?ml$/i.test(file.name)) {
                throw new Error(`Choose a .yaml or .yml file: ${file.name}`);
            }
            if (file.size > MAX_FILE_SIZE) {
                throw new Error(`${file.name} is larger than 1 MB.`);
            }
            if (incomingNames.has(file.name)) {
                throw new Error(`Two files are named ${file.name}. Rename one.`);
            }
            incomingNames.add(file.name);
            incoming.push({ name: file.name, text: await file.text() });
        }
        const combined = new Map(
            (state.example ? [] : state.files).map((file) => [file.name, file]),
        );
        incoming.forEach((file) => combined.set(file.name, file));
        if (combined.size > 20) {
            throw new Error('Keep no more than 20 files open.');
        }
        state.files = [...combined.values()];
        state.example = false;
        state.applicationName = '';
        for (const file of incoming) {
            try {
                const parsed = parseYaml(file.text, file.name);
                if (parsed.kind === 'network') {
                    state.networkName = file.name;
                } else {
                    state.applicationName = file.name;
                }
            } catch {
                state.networkName = file.name;
            }
        }
        await draw();
    } catch (error) {
        state.abort?.abort();
        state.revision++;
        resetDiagram();
        showError(error);
    }
}

function inspect(id) {
    if (!state.model) {
        return;
    }
    const node = state.model.nodes.find((candidate) => candidate.id === id);
    if (!node) {
        return;
    }
    graph
        .querySelectorAll('[data-node]')
        .forEach((item) => item.classList.toggle('selected', item.dataset.node === id));
    graph.querySelector('.connections').classList.add('has-selection');
    graph
        .querySelectorAll('.connection')
        .forEach((item) =>
            item.classList.toggle(
                'selected',
                item.dataset.source === id || item.dataset.target === id,
            ),
        );
    element('node-kind').textContent = node.kind === 'module' ? node.type : `MIDI ${node.kind}`;
    element('node-name').textContent = node.name;
    const binding = typeof node.binding === 'string' ? node.binding : node.binding?.id;
    element('node-description').textContent =
        node.kind === 'module'
            ? `${node.role[0].toUpperCase()}${node.role.slice(1)} · ${node.configuration.sources?.length ?? 0} sources · ${node.configuration.destinations?.length ?? 0} destinations`
            : binding
              ? `Bound to: ${binding}`
              : node.kind === 'bus'
                ? 'Broadcast bus: each subscriber receives every message published here.'
                : 'Logical port alias. Add an application YAML to see its physical binding.';
    const linked = (side) =>
        state.model.edges
            .filter((edge) => edge[side] === id)
            .map(
                (edge) =>
                    state.model.nodes.find(
                        (candidate) =>
                            candidate.id === edge[side === 'target' ? 'source' : 'target'],
                    ).name,
            );
    const connections = element('node-connections');
    connections.replaceChildren();
    for (const [label, names] of [
        ['Receives from', linked('target')],
        ['Sends to', linked('source')],
    ]) {
        if (!names.length) {
            continue;
        }
        const row = document.createElement('div');
        row.className = 'connection-list';
        const title = document.createElement('strong');
        title.textContent = label;
        row.append(title, names.join(', '));
        connections.append(row);
    }
    element('node-settings').textContent = node.configuration ? stringify(node.configuration) : '';
    element('node-settings').hidden = !node.configuration;
}

element('file-input').addEventListener('change', (event) => {
    importFiles([...event.target.files]);
    event.target.value = '';
});
let dragDepth = 0;
document.addEventListener('dragenter', (event) => {
    if (![...event.dataTransfer.types].includes('Files')) {
        return;
    }
    event.preventDefault();
    dragDepth++;
    document.body.classList.add('dragging');
});
document.addEventListener('dragover', (event) => {
    if ([...event.dataTransfer.types].includes('Files')) {
        event.preventDefault();
    }
});
document.addEventListener('dragleave', () => {
    if (--dragDepth <= 0) {
        dragDepth = 0;
        document.body.classList.remove('dragging');
    }
});
document.addEventListener('drop', (event) => {
    event.preventDefault();
    dragDepth = 0;
    document.body.classList.remove('dragging');
    importFiles([...event.dataTransfer.files]);
});
element('clear-files').addEventListener('click', () => {
    state.files = [];
    state.networkName = null;
    state.applicationName = null;
    state.example = false;
    draw();
});
element('load-example').addEventListener('click', () => {
    state.files = structuredClone(examples[element('example').value].files);
    state.networkName = null;
    state.applicationName = null;
    state.example = true;
    draw();
});
element('network-select').addEventListener('change', (event) => {
    state.networkName = event.target.value;
    draw();
});
element('application-select').addEventListener('change', (event) => {
    state.applicationName = event.target.value;
    draw();
});
element('apply-source').addEventListener('click', () => {
    const name = state.networkName ?? 'network.yaml';
    const file = { name, text: element('source-text').value };
    state.files = [...state.files.filter((current) => current.name !== name), file];
    state.networkName = name;
    state.example = false;
    draw();
});
element('direction').addEventListener('change', draw);
viewport.addEventListener('wheel', (event) => state.panzoom?.zoomWithWheel(event), {
    passive: false,
});
graph.addEventListener('panzoomchange', (event) => {
    element('zoom-label').textContent = `${Math.round(event.detail.scale * 100)}%`;
});
graph.addEventListener('click', (event) => {
    const node = event.target.closest('[data-node]');
    if (node) {
        inspect(node.dataset.node);
    }
});
graph.addEventListener('keydown', (event) => {
    if (event.key !== 'Enter' && event.key !== ' ') {
        return;
    }
    const node = event.target.closest('[data-node]');
    if (node) {
        event.preventDefault();
        inspect(node.dataset.node);
    }
});
element('fit').addEventListener('click', fit);
element('zoom-in').addEventListener('click', () => state.panzoom?.zoomIn());
element('zoom-out').addEventListener('click', () => state.panzoom?.zoomOut());
new ResizeObserver(() => {
    if (state.panzoom) {
        requestAnimationFrame(fit);
    }
}).observe(viewport);
element('download').addEventListener('click', () => {
    if (!state.svg) {
        return;
    }
    const url = URL.createObjectURL(new Blob([state.svg], { type: 'image/svg+xml;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `${state.model.name.replace(/\.ya?ml$/i, '')}-network.svg`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
});
state.files = structuredClone(examples.performance.files);
draw();
