#!/usr/bin/env node
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, resolve } from "node:path";

const MANIFEST_SUFFIX = "_client-reference-manifest.js";
const ASSIGNMENT = /globalThis\.__RSC_MANIFEST\[("(?:[^"\\]|\\.)*")\]\s*=\s*(\{[\s\S]*)/g;
const ASSET_PREFIX = "/_next/";

/**
 * Collects every client-reference manifest a build emitted, one per route that renders client
 * components.
 * @param {string} directory absolute path to search
 * @returns {string[]} absolute manifest paths
 */
function findManifests(directory) {
    if (!existsSync(directory)) {
        return [];
    }
    const found = [];
    for (const entry of readdirSync(directory)) {
        const path = join(directory, entry);
        if (statSync(path).isDirectory()) {
            found.push(...findManifests(path));
        } else if (entry.endsWith(MANIFEST_SUFFIX)) {
            found.push(path);
        }
    }
    return found;
}

/**
 * Maps a manifest's chunk reference to the path the build should have emitted it at.
 * @param {string} reference a chunk URL as the manifest records it
 * @param {string} buildRoot absolute path of the build output directory
 * @returns {string} absolute path the chunk must exist at
 */
function emittedPath(reference, buildRoot) {
    const withoutQuery = reference.split("?")[0];
    const relativePath = withoutQuery.startsWith(ASSET_PREFIX)
        ? withoutQuery.slice(ASSET_PREFIX.length)
        : withoutQuery.replace(/^\/+/, "");
    return join(buildRoot, relativePath);
}

/**
 * Reads the chunk references a single manifest declares, keyed by the route that needs them.
 * @param {string} manifestPath absolute path to a client-reference manifest
 * @returns {Map<string, Set<string>>} chunk reference to the routes requiring it
 */
function readReferences(manifestPath) {
    const source = readFileSync(manifestPath, "utf8");
    const references = new Map();
    for (const [, quotedRoute, payload] of source.matchAll(ASSIGNMENT)) {
        const route = JSON.parse(quotedRoute);
        const manifest = JSON.parse(payload.trimEnd().replace(/;$/, ""));
        for (const module of Object.values(manifest.clientModules ?? {})) {
            for (const chunk of module.chunks ?? []) {
                if (!references.has(chunk)) {
                    references.set(chunk, new Set());
                }
                references.get(chunk).add(route);
            }
        }
    }
    return references;
}

/**
 * Fails when a route's manifest names a chunk the build never emitted. Next.js serves such a route
 * happily until the browser requests the missing chunk, so nothing short of loading that specific
 * route in a browser catches it — a smoke test that fetches one page cannot.
 * @param {string} buildRoot path to the build output directory, `.next` by default
 * @returns {number} process exit code
 */
function verify(buildRoot) {
    const root = resolve(buildRoot);
    const manifests = findManifests(join(root, "server", "app"));
    if (manifests.length === 0) {
        console.error(`No client-reference manifests under ${root}/server/app — is this a production build?`);
        return 1;
    }

    const routesByChunk = new Map();
    for (const manifest of manifests) {
        for (const [chunk, routes] of readReferences(manifest)) {
            if (!routesByChunk.has(chunk)) {
                routesByChunk.set(chunk, new Set());
            }
            for (const route of routes) {
                routesByChunk.get(chunk).add(route);
            }
        }
    }

    const missing = [...routesByChunk.keys()]
        .filter((chunk) => !existsSync(emittedPath(chunk, root)))
        .sort();

    if (missing.length > 0) {
        console.error(
            `${missing.length} chunk(s) referenced by a route manifest were never emitted by the build:`,
        );
        for (const chunk of missing) {
            const routes = [...routesByChunk.get(chunk)].sort().join(", ");
            console.error(`  ${chunk}\n    expected at ${relative(root, emittedPath(chunk, root))}\n    required by ${routes}`);
        }
        return 1;
    }

    console.log(
        `Verified ${routesByChunk.size} chunk reference(s) across ${manifests.length} route manifest(s) in ${root}.`,
    );
    return 0;
}

process.exit(verify(process.argv[2] ?? ".next"));
