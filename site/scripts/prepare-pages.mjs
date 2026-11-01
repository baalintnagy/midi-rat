import { cp, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..');
const output = resolve(root, '_site');
await mkdir(output, { recursive: true });
// Deliberate allowlist: no personal configurations, tests, source maps, or node_modules.
for (const name of [
    'index.html',
    'styles.css',
    'app.js',
    'network',
    'ui',
    'vendor',
    'favicon.svg',
]) {
    await cp(resolve(root, name), resolve(output, name), { recursive: true });
}
console.log(`Static Pages artifact: ${output}`);
