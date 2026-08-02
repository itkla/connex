import { readFileSync, existsSync } from 'node:fs';
import path from 'node:path';

/**
 * Summarises a route/state matrix run into a reviewable index.
 *
 * The manifest is JSON Lines so parallel workers can append safely, which makes it awkward to read
 * directly. This collapses it into per-route and per-state coverage plus the faults worth triaging,
 * so a reviewer can see what was actually exercised without opening several hundred screenshots.
 *
 * Usage: `node test/e2e/matrix/summarize.mjs [artifactDir]`
 */

const artifactDir = process.argv[2] ?? path.resolve('test/e2e/.artifacts/matrix');
const manifestPath = path.join(artifactDir, 'manifest.jsonl');
const runInfoPath = path.join(artifactDir, 'run-info.json');

if (!existsSync(manifestPath)) {
    console.error(`No manifest at ${manifestPath} — run the matrix first.`);
    process.exit(1);
}

const entries = readFileSync(manifestPath, 'utf8')
    .split('\n')
    .filter((line) => line.trim().length > 0)
    .map((line) => JSON.parse(line));

const runInfo = existsSync(runInfoPath) ? JSON.parse(readFileSync(runInfoPath, 'utf8')) : {};

const byRoute = new Map();
const byState = new Map();
const axisCombos = new Set();
const faulted = [];

for (const entry of entries) {
    const route = byRoute.get(entry.routeId) ?? { cells: 0, states: new Set() };
    route.cells += 1;
    route.states.add(entry.state);
    byRoute.set(entry.routeId, route);

    byState.set(entry.state, (byState.get(entry.state) ?? 0) + 1);
    axisCombos.add(`${entry.axes.viewport}/${entry.axes.locale}/${entry.axes.theme}`);
    if (Array.isArray(entry.faults) && entry.faults.length > 0) faulted.push(entry);
}

console.log('# Route/state matrix run');
console.log(`captured: ${runInfo.capturedAt ?? 'unknown'}   commit: ${runInfo.commit ?? 'unknown'}`);
console.log(`cells: ${entries.length}   routes: ${byRoute.size}   axis combinations: ${axisCombos.size}`);
console.log('');

console.log('## States exercised');
for (const [state, count] of [...byState].sort((a, b) => b[1] - a[1])) {
    console.log(`  ${String(count).padStart(4)}  ${state}`);
}
console.log('');

console.log('## Coverage by route');
for (const [routeId, route] of [...byRoute].sort()) {
    console.log(`  ${routeId.padEnd(24)} ${String(route.cells).padStart(3)} cells  [${[...route.states].sort().join(', ')}]`);
}
console.log('');

console.log(`## Cells with faults: ${faulted.length}`);
for (const entry of faulted) {
    const axes = `${entry.axes.viewport}/${entry.axes.locale}/${entry.axes.theme}`;
    console.log(`  ${entry.routeId} @ ${axes} [${entry.state}]`);
    for (const fault of entry.faults) console.log(`      ${fault.kind}: ${fault.text}`);
}

if (Array.isArray(runInfo.scopeCaveats) && runInfo.scopeCaveats.length > 0) {
    console.log('');
    console.log('## Scope caveats (this run does NOT cover these)');
    for (const caveat of runInfo.scopeCaveats) console.log(`  - ${caveat}`);
}
