import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { resolve, extname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('../android/app/src/main/assets/', import.meta.url)));
const port = Number(process.env['PORT'] || 4173);
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon' };
const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url || '/', 'http://127.0.0.1');
    let path = decodeURIComponent(url.pathname);
    if (path === '/') path = '/assets/web/index.html';
    if (!path.startsWith('/assets/')) { response.writeHead(404).end('Not found'); return; }
    const file = resolve(root, path.slice('/assets/'.length));
    if (!file.startsWith(root + sep) && file !== root) { response.writeHead(403).end('Forbidden'); return; }
    if (!(await stat(file)).isFile()) { response.writeHead(404).end('Not found'); return; }
    response.writeHead(200, { 'Content-Type': types[extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
    response.end(await readFile(file));
  } catch { response.writeHead(404).end('Not found. Run npm run build first.'); }
});
server.listen(port, '127.0.0.1', () => console.log(`Browser simulation: http://127.0.0.1:${port}`));
