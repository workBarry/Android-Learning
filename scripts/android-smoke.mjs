import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// 開發用整合檢查，會把本專案安裝到已連接的測試裝置。
const root = fileURLToPath(new URL('../', import.meta.url));
const build = spawnSync(process.execPath, [resolve(root, 'scripts/android-build.mjs'), '--smoke'], { cwd: root, stdio: 'inherit' });
if (build.status !== 0) process.exit(build.status ?? 1);
const sdk = process.env['ANDROID_HOME'] || process.env['ANDROID_SDK_ROOT'] ||
  (process.env['LOCALAPPDATA'] ? resolve(process.env['LOCALAPPDATA'], 'Android/Sdk') : '');
const adb = resolve(sdk, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
if (!existsSync(adb)) throw new Error('找不到 adb，請設定 ANDROID_HOME。');
const device = process.env['ANDROID_SERIAL'] ? ['-s', process.env['ANDROID_SERIAL']] : [];
function run(args, capture = false) {
  const result = spawnSync(adb, [...device, ...args], { cwd: root, encoding: 'utf8', stdio: capture ? 'pipe' : 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(result.stderr || 'Android 檢查指令失敗。');
  return result.stdout || '';
}
run(['install', '-r', resolve(root, 'android/app/build/outputs/apk/debug/app-debug.apk')]);
run(['install', '-r', resolve(root, 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]);
const output = run(['shell', 'am', 'instrument', '-w', 'dev.learning.navigation.test/dev.learning.navigation.BridgeSmokeInstrumentation'], true);
console.log(output);
if (!output.includes('OK (1 bridge smoke test)')) process.exit(1);
