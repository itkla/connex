import { readFileSync, existsSync } from 'node:fs';
import path from 'node:path';

/**
 * Summarises a route/state matrix run into a reviewable index.
 *
 * The manifest is JSON Lines so parallel workers can append safely, which makes it awkward to read
 * directly. This collapses it into per-route and per-state coverage plus everything worth triaging,
 * so a reviewer can see what was actually exercised without opening several hundred screenshots.
 *
 * A cell needs triage when it produced a console/page fault OR a failing HTTP response that no filed
 * suppression covers; counting only the first would let a run fail on an unexpected 404 or 500 and
 * still report zero problems. Cells that did not render as the route they requested are reported
 * separately and first, because a redirect recorded as content is the one failure that would
 * invalidate every other number on the page.
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
const needsTriage = [];
const knownFiled = new Map();
const misdirected = [];

/**
 * Splits a cell's recorded response failures into the ones a filed suppression covers and the ones
 * that still need triage.
 * @param {{responseFailures?: {status: number, url: string, knownIssue?: string}[]}} entry one manifest line
 */
function splitResponses(entry) {
    const failures = Array.isArray(entry.responseFailures) ? entry.responseFailures : [];
    return {
        unexpected: failures.filter((failure) => !failure.knownIssue),
        known: failures.filter((failure) => failure.knownIssue),
    };
}

for (const entry of entries) {
    const route = byRoute.get(entry.routeId) ?? { cells: 0, states: new Set() };
    route.cells += 1;
    route.states.add(entry.state);
    byRoute.set(entry.routeId, route);

    byState.set(entry.state, (byState.get(entry.state) ?? 0) + 1);
    axisCombos.add(`${entry.axes.viewport}/${entry.axes.locale}/${entry.axes.theme}`);

    const faults = Array.isArray(entry.faults) ? entry.faults : [];
    const { unexpected, known } = splitResponses(entry);
    for (const failure of known) {
        knownFiled.set(failure.knownIssue, (knownFiled.get(failure.knownIssue) ?? 0) + 1);
    }
    if (faults.length > 0 || unexpected.length > 0) needsTriage.push({ entry, faults, unexpected });

    const finalPath = typeof entry.finalPath === 'string' ? entry.finalPath : null;
    const requestedPath = String(entry.path).split('?')[0];
    if (entry.state === 'unexpected-landing' || (finalPath !== null && finalPath !== requestedPath)) {
        misdirected.push({ entry, finalPath });
    }
}

console.log('# Route/state matrix run');
console.log(`captured: ${runInfo.capturedAt ?? 'unknown'}   commit: ${runInfo.commit ?? 'unknown'}`);
console.log(`cells: ${entries.length}   routes: ${byRoute.size}   axis combinations: ${axisCombos.size}`);
if (runInfo.seed === null) {
    console.log('seed: UNKNOWN — usernames were overridden without declaring seeder parameters');
} else if (runInfo.seed) {
    console.log(`seed: ${runInfo.seed.profile}/${runInfo.seed.seed} (${runInfo.seed.workspaces} workspaces)`);
}
console.log('');

const withoutFinalPath = entries.filter((entry) => typeof entry.finalPath !== 'string').length;
console.log(`## Cells that did not render as the route they requested: ${misdirected.length}`);
for (const { entry, finalPath } of misdirected) {
    console.log(`  ${entry.routeId} [${entry.state}] requested ${entry.path} -> landed ${finalPath ?? 'unrecorded'}`);
}
if (withoutFinalPath > 0) {
    console.log(`  NOTE: ${withoutFinalPath} cell(s) recorded no final path — they predate landing verification`);
    console.log('        and cannot be shown to have rendered the route they claim.');
}
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

console.log(`## Cells needing triage: ${needsTriage.length}`);
for (const { entry, faults, unexpected } of needsTriage) {
    const axes = `${entry.axes.viewport}/${entry.axes.locale}/${entry.axes.theme}`;
    console.log(`  ${entry.routeId} @ ${axes} [${entry.state}]`);
    for (const fault of faults) console.log(`      ${fault.kind}: ${fault.text}`);
    for (const failure of unexpected) console.log(`      response: ${failure.status} ${failure.url}`);
}
console.log('');

console.log(`## Known filed failures, suppressed from pass/fail: ${knownFiled.size}`);
for (const [issue, count] of [...knownFiled].sort((a, b) => b[1] - a[1])) {
    console.log(`  ${String(count).padStart(4)}  ${issue}`);
}

if (Array.isArray(runInfo.scopeCaveats) && runInfo.scopeCaveats.length > 0) {
    console.log('');
    console.log('## Scope caveats (this run does NOT cover these)');
    for (const caveat of runInfo.scopeCaveats) console.log(`  - ${caveat}`);
}
