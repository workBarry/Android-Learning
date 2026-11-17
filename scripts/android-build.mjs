import { spawnSync } from 'node:child_process';
import { existsSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const android = fileURLToPath(new URL('../android/', import.meta.url));
const properties = resolve(android, 'local.properties');
if (!existsSync(properties)) {
  const sdk = process.env['ANDROID_HOME'] || process.env['ANDROID_SDK_ROOT'] ||
    (process.env['LOCALAPPDATA'] ? resolve(process.env['LOCALAPPDATA'], 'Android/Sdk') : '');
  if (!sdk || !existsSync(sdk)) {
    console.error('找不到 Android SDK。請先用 Android Studio 開啟 android 資料夾，或設定 ANDROID_HOME。');
    process.exit(1);
  }
  writeFileSync(properties, `sdk.dir=${sdk.replaceAll('\\', '/').replaceAll(':', '\\:')}\n`);
}
const args = [':app:assembleDebug'];
if (process.argv.includes('--smoke')) args.push(':app:assembleDebugAndroidTest');
args.push('--console=plain');
const result = process.platform === 'win32'
  ? spawnSync('cmd.exe', ['/d', '/s', '/c', 'gradlew.bat', ...args], { cwd: android, stdio: 'inherit' })
  : spawnSync('./gradlew', args, { cwd: android, stdio: 'inherit' });
if (result.error) console.error(result.error.message);
process.exit(result.status ?? 1);
