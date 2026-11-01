import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, resolve, sep } from 'node:path';

const root = resolve(import.meta.dirname, '..');
const mime = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.json': 'application/json',
    '.svg': 'image/svg+xml',
};
http.createServer(async (request, response) => {
    try {
        const pathname = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
        const file = resolve(root, `.${pathname === '/' ? '/index.html' : pathname}`);
        if (!file.startsWith(`${root}${sep}`)) {
            response.writeHead(403).end();
            return;
        }
        response.setHeader('Content-Type', mime[extname(file)] || 'application/octet-stream');
        response.setHeader('Cache-Control', 'no-store');
        response.end(await readFile(file));
    } catch {
        response.writeHead(404).end('Not found');
    }
}).listen(4174, '127.0.0.1', () => console.log('Local: http://127.0.0.1:4174'));
