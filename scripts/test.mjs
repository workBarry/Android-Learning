import ts from 'typescript';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
mkdirSync(resolve(root, '.test-dist'), { recursive: true });
const source = readFileSync(resolve(root, 'web/shared/bridge.ts'), 'utf8');
const output = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } });
writeFileSync(resolve(root, '.test-dist/bridge.js'), output.outputText);
const result = spawnSync(process.execPath, ['--test', 'tests/communication.test.mjs'], { cwd: root, stdio: 'inherit' });
process.exit(result.status ?? 1);
