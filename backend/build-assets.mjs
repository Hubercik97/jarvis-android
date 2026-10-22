import { mkdir, copyFile, cp } from 'node:fs/promises';
const target = new URL('./public/', import.meta.url);
await mkdir(target, { recursive: true });
for (const name of ['index.html','styles.css','app.js','cloud-ui.js','sw.js','manifest.webmanifest']) {
  await copyFile(new URL('../' + name, import.meta.url), new URL(name, target));
}
await cp(new URL('../icons/', import.meta.url), new URL('icons/', target), { recursive: true });
console.log('Static PWA assets copied; source and secrets are excluded.');
