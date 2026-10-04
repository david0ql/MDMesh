import { createRequire } from 'module';
const { chromium } = createRequire('/Users/david/Documents/own/MDMesh/scripts/shots/package.json')('playwright');
// Regenera la guía: node docs/guia-sin-formatear/topdf.mjs $PWD/docs/guia-sin-formatear/guia.html $PWD/web/public/guia-inscribir-sin-formatear.pdf
const [html, out, png] = process.argv.slice(2);
const b = await chromium.launch();
const p = await b.newPage();
await p.goto('file://' + html);
await p.pdf({ path: out, format: 'A4', printBackground: true, margin: { top: '14mm', bottom: '16mm', left: '14mm', right: '14mm' } });
await p.setViewportSize({ width: 794, height: 1123 });
await b.close();
