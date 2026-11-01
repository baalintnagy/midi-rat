import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import test from 'node:test';
import { stringify } from '../vendor/yaml/index.js';
import { parseYaml, selectDocuments } from '../network/yaml.js';
import { buildNetwork } from '../network/model.js';
import { examples } from '../network/examples.js';
import { layoutNetwork } from '../network/layout.js';
import { renderSvg } from '../network/svg.js';

const network = (modules, buses = []) =>
    parseYaml(
        stringify({
            schemaVersion: 1,
            network: { buses, modules },
        }),
        'network.yaml',
    );
const processor = (name, sources, destinations) => ({
    name,
    type: 'midi-message-filter',
    sources,
    destinations,
    settings: {},
});

test('the example graph retains buses, fan-out, observers and physical bindings', () => {
    const selected = selectDocuments(examples.performance.files);
    const graph = buildNetwork(selected.network, selected.application);
    assert.equal(graph.nodes.length, 10);
    assert.equal(graph.edges.length, 9);
    assert.deepEqual(graph.warnings, []);
    const node = (name) => graph.nodes.find((item) => item.name === name);
    assert.equal(node('keyboard').binding, 'Example MIDI Keyboard');
    assert.equal(node('sounding').kind, 'bus');
    assert.equal(node('Chord display').role, 'monitor');
    assert(
        graph.edges.some(
            (edge) => edge.source === node('Sostenuto').id && edge.target === node('synth').id,
        ),
    );
    assert(
        graph.edges.some(
            (edge) =>
                edge.source === node('sounding').id && edge.target === node('Chord display').id,
        ),
    );
    assert(
        !graph.edges.some(
            (edge) =>
                edge.source === node('musical').id && edge.target === node('Chord display').id,
        ),
    );
});

for (const [name, example] of Object.entries(examples)) {
    test(`generic example: ${name}`, () => {
        const selected = selectDocuments(example.files);
        const graph = buildNetwork(selected.network, selected.application);
        assert(graph.nodes.length > 0);
        assert(graph.edges.length > 0);
    });
}

for (const name of [
    'wmm-application.yaml',
    'wmm-application-monitor.yaml',
    'wms-application-monitor.yaml',
    'wmm-application-sostenuto.yaml',
    'wms-application-sostenuto.yaml',
    'wmm-application-sostenuto-launchpad.yaml',
    'wms-application-sostenuto-launchpad.yaml',
]) {
    test(`real application/network pair: ${name}`, async () => {
        const root = resolve(import.meta.dirname, '../../config');
        const application = parseYaml(await readFile(resolve(root, name), 'utf8'), name);
        const networkName = application.data.application.networkFile.replace('./', '');
        const document = parseYaml(await readFile(resolve(root, networkName), 'utf8'), networkName);
        const graph = buildNetwork(document, application);
        assert.equal(
            graph.nodes.filter((node) => node.kind === 'module').length,
            document.data.network.modules.length,
        );
        assert.deepEqual(graph.warnings, []);
    });
}

test('network-only imports infer ports and make the inference explicit', () => {
    const graph = buildNetwork(network([processor('filter', ['keys'], ['synth'])]));
    assert.equal(graph.nodes.find((node) => node.name === 'keys').kind, 'input');
    assert.equal(graph.nodes.find((node) => node.name === 'synth').kind, 'output');
    assert.match(graph.warnings[0], /Network only/);
});

test('external module settings are preserved, with a role-inference notice', () => {
    const document = network([
        {
            name: 'delay',
            type: 'custom-delay',
            sources: ['keys'],
            destinations: ['synth'],
            settings: { arbitrary: [1, 2, 3] },
        },
    ]);
    const graph = buildNetwork(document);
    assert.equal(graph.nodes[0].role, 'processor');
    assert.deepEqual(graph.nodes[0].configuration.settings, { arbitrary: [1, 2, 3] });
    assert.match(graph.warnings[0], /Custom module/);
});

test('module instance names and endpoint aliases have independent identities', () => {
    const graph = buildNetwork(network([processor('keys', ['keys'], ['synth'])]));
    assert.equal(graph.nodes.filter((node) => node.name === 'keys').length, 2);
    assert.equal(new Set(graph.nodes.map((node) => node.id)).size, graph.nodes.length);
});

test('routing cycles are rejected, including self-subscription through a bus', () => {
    assert.throws(
        () => buildNetwork(network([processor('one', ['bus'], ['bus'])], ['bus'])),
        /routing cycle/,
    );
    assert.throws(
        () =>
            buildNetwork(
                network(
                    [processor('one', ['a'], ['b']), processor('two', ['b'], ['a'])],
                    ['a', 'b'],
                ),
            ),
        /routing cycle/,
    );
});

test('invalid structures do not silently produce plausible diagrams', () => {
    for (const [modules, buses, expected] of [
        [
            [processor('same', ['keys'], ['out']), processor('same', ['keys'], ['out'])],
            [],
            /Duplicate module/,
        ],
        [[processor('one', ['keys', 'keys'], ['out'])], [], /unique/],
        [[processor('one', ['keys'], ['out'])], ['bus', 'bus'], /unique/],
        [[processor('one', ['keys'], ['keys'])], [], /Cannot use/],
        [
            [
                {
                    name: 'monitor',
                    type: 'midi-monitor',
                    sources: ['keys'],
                    destinations: [],
                    settings: {},
                },
            ],
            [],
            /Observers/,
        ],
        [
            [
                {
                    name: 'emitter',
                    type: 'midi-emitter',
                    sources: [],
                    destinations: ['out'],
                    settings: {},
                },
            ],
            [],
            /Emitters/,
        ],
        [[{ name: 'missing', type: 'midi-monitor', sources: ['keys'] }], [], /mapping/],
    ]) {
        assert.throws(() => buildNetwork(network(modules, buses)), expected);
    }
});

test('unknown physical aliases are rejected when an application is present', () => {
    const selected = selectDocuments(examples.performance.files);
    const data = structuredClone(selected.network.data);
    data.network.modules[0].sources.push('typo');
    const bad = parseYaml(stringify(data), 'network.yaml');
    assert.throws(() => buildNetwork(bad, selected.application), /Unknown endpoint: typo/);
});

test('syntax errors and duplicate YAML keys include source locations', () => {
    for (const source of [
        'schemaVersion: 1\nnetwork: [',
        'schemaVersion: 1\nnetwork: {}\nnetwork: {}',
    ]) {
        assert.throws(
            () => parseYaml(source, 'bad.yaml'),
            (error) => {
                assert.equal(error.source, 'bad.yaml');
                assert(error.line > 0);
                return true;
            },
        );
    }
});

test('aliases, custom tags, multiple documents and oversized input are rejected', () => {
    for (const source of [
        'schemaVersion: 1\nnetwork: &a {buses: []}\ncopy: *a',
        'schemaVersion: 1\nnetwork: !custom {}',
        'schemaVersion: 1\nnetwork: {}\n---\nnetwork: {}',
        '#'.repeat(1_000_001),
    ]) {
        assert.throws(() => parseYaml(source, 'bad.yaml'));
    }
});

test('application references select the correct network and support Windows separators', () => {
    const files = [examples.local.files[0], ...structuredClone(examples.performance.files)];
    files[2].text = files[2].text.replace('./network.yaml', '..\\networks\\network.yaml');
    const selected = selectDocuments(files);
    assert.equal(selected.network.name, 'network.yaml');
    assert.throws(() => selectDocuments(files, 'local.yaml'), /not local.yaml/);
    assert.equal(selectDocuments(files, 'local.yaml', '').application, null);
    assert.throws(() => selectDocuments([files[2]]), /referenced network file/);
    assert.throws(() => selectDocuments([files[0], files[0]]), /same name/);
});

for (const direction of ['RIGHT', 'DOWN']) {
    test(`ELK produces finite, nonoverlapping nodes and routed SVG in ${direction} direction`, async () => {
        const selected = selectDocuments(examples.performance.files);
        const model = buildNetwork(selected.network, selected.application);
        const layout = await layoutNetwork(model, direction);
        assert(layout.width > 0 && layout.height > 0);
        assert.equal(layout.children.length, model.nodes.length);
        assert.equal(layout.edges.length, model.edges.length);
        for (const node of layout.children) {
            assert(Number.isFinite(node.x) && Number.isFinite(node.y));
            for (const other of layout.children.filter((candidate) => candidate.id !== node.id)) {
                assert(
                    node.x + node.width <= other.x ||
                        other.x + other.width <= node.x ||
                        node.y + node.height <= other.y ||
                        other.y + other.height <= node.y,
                );
            }
        }
        for (const edge of layout.edges) {
            assert(edge.sections?.length > 0);
        }
        const svg = renderSvg(model, layout);
        assert(svg.startsWith('<svg'));
        assert.equal((svg.match(/class="graph-node"/g) ?? []).length, model.nodes.length);
        assert(!svg.includes('NaN'));
    });
}

for (const direction of ['RIGHT', 'DOWN']) {
    test(`emitter and sostenuto routes remain visually separate in ${direction}`, async () => {
        const text = await readFile(
            resolve(import.meta.dirname, '../../config/network-sostenuto-launchpad.yaml'),
            'utf8',
        );
        const model = buildNetwork(parseYaml(text, 'network.yaml'));
        const emitter = model.nodes.find((node) => node.name === 'EMT');
        const targets = model.edges
            .filter((edge) => edge.source === emitter.id)
            .map((edge) => model.nodes.find((node) => node.id === edge.target).name);
        assert.deepEqual(targets, ['postSSB']);
        const layout = await layoutNetwork(model, direction);
        const segments = layout.edges.flatMap((edge) =>
            edge.sections.flatMap((section) => {
                const points = [
                    section.startPoint,
                    ...(section.bendPoints ?? []),
                    section.endPoint,
                ];
                return points
                    .slice(1)
                    .map((end, index) => ({ id: edge.id, start: points[index], end }));
            }),
        );
        for (const a of segments) {
            for (const b of segments) {
                if (a.id === b.id) {
                    continue;
                }
                for (const [axis, along] of [
                    ['x', 'y'],
                    ['y', 'x'],
                ]) {
                    const collinear =
                        Math.abs(a.start[axis] - a.end[axis]) < 0.001 &&
                        Math.abs(b.start[axis] - b.end[axis]) < 0.001 &&
                        Math.abs(a.start[axis] - b.start[axis]) < 0.001;
                    const overlap =
                        Math.min(
                            Math.max(a.start[along], a.end[along]),
                            Math.max(b.start[along], b.end[along]),
                        ) -
                        Math.max(
                            Math.min(a.start[along], a.end[along]),
                            Math.min(b.start[along], b.end[along]),
                        );
                    assert(!collinear || overlap < 0.001, `${a.id} overlaps ${b.id}`);
                }
            }
        }
    });
}

test('a selected network still requires a valid application networkFile', () => {
    const application = { name: 'application.yaml', text: 'schemaVersion: 1\napplication: {}' };
    const file = { name: 'network.yaml', text: 'schemaVersion: 1\nnetwork: {modules: []}' };
    assert.throws(
        () => selectDocuments([file, application], 'network.yaml'),
        /Expected a networkFile path/,
    );
});

test('uploaded names are escaped as text, never embedded as SVG markup or attributes', async () => {
    const malicious = '<script>alert("x")</script>&';
    const model = buildNetwork(network([processor(malicious, ['keys'], ['synth'])]));
    const svg = renderSvg(model, await layoutNetwork(model));
    assert(!svg.includes('<script>'));
    assert(svg.includes('&lt;script&gt;'));
    assert(svg.includes('&quot;x&quot;'));
});
