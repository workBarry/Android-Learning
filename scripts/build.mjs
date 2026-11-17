import { spawnSync } from 'node:child_process';
import { cpSync, mkdirSync, existsSync, rmSync } from 'node:fs';
import { resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const assets = resolve(root, 'android/app/src/main/assets/web');
// 這裡只有建置產物；先確認位於本專案，再清除舊版的雜湊檔案。
if (!assets.startsWith(resolve(root) + sep)) throw new Error('Unexpected assets directory.');
rmSync(assets, { recursive: true, force: true });
for (const app of ['modern', 'legacy']) {
  const result = spawnSync(process.execPath, [resolve(root, 'node_modules/@angular/cli/bin/ng.js'), 'build', app, '--configuration', 'production'], { cwd: root, stdio: 'inherit' });
  if (result.status !== 0) process.exit(result.status ?? 1);
  const source = resolve(root, 'dist', app, 'browser');
  if (!existsSync(resolve(source, 'index.html'))) throw new Error(`Missing ${app} browser output.`);
  const destination = resolve(root, 'android/app/src/main/assets/web', app);
  mkdirSync(destination, { recursive: true });
  cpSync(source, destination, { recursive: true });
}
mkdirSync(assets, { recursive: true });
cpSync(resolve(root, 'web/preview'), assets, { recursive: true });
console.log('Built both Web apps and copied them into Android assets.');
