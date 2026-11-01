import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import test from 'node:test';

const root = resolve(import.meta.dirname, '..');

async function sameTree(source, target) {
    const entries = await readdir(source, { withFileTypes: true });
    assert.deepEqual((await readdir(target)).sort(), entries.map((entry) => entry.name).sort());
    for (const entry of entries) {
        const original = resolve(source, entry.name);
        const copy = resolve(target, entry.name);
        if (entry.isDirectory()) {
            await sameTree(original, copy);
        } else {
            assert.deepEqual(await readFile(copy), await readFile(original), copy);
        }
    }
}

test('vendored runtime libraries and licenses match the locked npm packages', async () => {
    await sameTree(resolve(root, 'node_modules/yaml/browser'), resolve(root, 'vendor/yaml'));
    const assets = [
        ['yaml/LICENSE', 'YAML-LICENSE'],
        ['elkjs/lib/elk.bundled.js', 'elk.bundled.js'],
        ['elkjs/lib/elk-worker.min.js', 'elk-worker.min.js'],
        ['elkjs/LICENSE.md', 'ELK-LICENSE.md'],
        ['@panzoom/panzoom/dist/panzoom.es.js', 'panzoom.js'],
        ['@panzoom/panzoom/MIT-License.txt', 'PANZOOM-LICENSE.txt'],
    ];
    for (const [source, target] of assets) {
        assert.deepEqual(
            await readFile(resolve(root, 'vendor', target)),
            await readFile(resolve(root, 'node_modules', source)),
            target,
        );
    }
});
