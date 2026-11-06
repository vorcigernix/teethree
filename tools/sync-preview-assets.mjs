import { createRequire } from 'node:module';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

if (!process.argv[2]) throw new Error('Usage: node tools/sync-preview-assets.mjs /path/to/t3code');
const source = resolve(process.argv[2]);
const require = createRequire(import.meta.url);
const output = resolve(dirname(fileURLToPath(import.meta.url)), '../app/src/main/assets/previews');
await mkdir(output, { recursive: true });
for (const name of ['device', 'preview']) {
  const script = require(resolve(source, `apps/mobile/.generated/${name}-stream/index.js`));
  if (typeof script !== 'string' || !script.includes(`T3${name === 'device' ? 'Device' : 'Preview'}Stream`)) {
    throw new Error(`Generate the mobile ${name} stream asset in T3 Code first.`);
  }
  await writeFile(resolve(output, `${name}-stream.js`), script.replaceAll('ReactNativeWebView', 'T3NativePreview'));
}
