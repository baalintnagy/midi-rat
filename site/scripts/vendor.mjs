import { cp, mkdir, readFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..');
const assets = [
    ['yaml/browser', 'yaml'],
    ['yaml/LICENSE', 'YAML-LICENSE'],
    ['elkjs/lib/elk.bundled.js', 'elk.bundled.js'],
    ['elkjs/lib/elk-worker.min.js', 'elk-worker.min.js'],
    ['elkjs/LICENSE.md', 'ELK-LICENSE.md'],
    ['@panzoom/panzoom/dist/panzoom.es.js', 'panzoom.js'],
    ['@panzoom/panzoom/MIT-License.txt', 'PANZOOM-LICENSE.txt'],
];
await mkdir(resolve(root, 'vendor'), { recursive: true });
for (const [source, destination] of assets) {
    await cp(resolve(root, 'node_modules', source), resolve(root, 'vendor', destination), {
        recursive: true,
    });
}
const packages = ['yaml', 'elkjs', '@panzoom/panzoom'];
for (const name of packages) {
    const metadata = JSON.parse(
        await readFile(resolve(root, 'node_modules', name, 'package.json')),
    );
    console.log(`${name} ${metadata.version}`);
}
