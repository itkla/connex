#!/usr/bin/env node
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, resolve } from "node:path";

const MANIFEST_SUFFIX = "_client-reference-manifest.js";
const ASSIGNMENT = /globalThis\.__RSC_MANIFEST\[("(?:[^"\\]|\\.)*")\]\s*=\s*(\{[\s\S]*)/g;
const ASSET_PREFIX = "/_next/";
const ROUTES_SHOWN = 5;

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

function emittedPath(reference, buildRoot) {
    const withoutQuery = reference.split("?")[0];
    const relativePath = withoutQuery.startsWith(ASSET_PREFIX)
        ? withoutQuery.slice(ASSET_PREFIX.length)
        : withoutQuery.replace(/^\/+/, "");
    return join(buildRoot, relativePath);
}

function declaredAssets(manifest) {
    const assets = [];
    for (const module of Object.values(manifest.clientModules ?? {})) {
        assets.push(...(module.chunks ?? []));
    }
    for (const entry of Object.values(manifest.entryCSSFiles ?? {})) {
        for (const stylesheet of entry ?? []) {
            if (typeof stylesheet === "string") {
                assets.push(stylesheet);
            } else if (stylesheet?.path && !stylesheet.inlined) {
                assets.push(stylesheet.path);
            }
        }
    }
    return assets;
}

function readReferences(manifestPath) {
    const source = readFileSync(manifestPath, "utf8");
    const references = new Map();
    for (const [, quotedRoute, payload] of source.matchAll(ASSIGNMENT)) {
        const route = JSON.parse(quotedRoute);
        const manifest = JSON.parse(payload.trimEnd().replace(/;$/, ""));
        for (const asset of declaredAssets(manifest)) {
            if (!references.has(asset)) {
                references.set(asset, new Set());
            }
            references.get(asset).add(route);
        }
    }
    return references;
}

function verify(buildRoot) {
    const root = resolve(buildRoot);
    const manifests = findManifests(join(root, "server", "app"));
    if (manifests.length === 0) {
        console.error(`No client-reference manifests under ${root}/server/app — is this a production build?`);
        return 1;
    }

    const routesByAsset = new Map();
    for (const manifest of manifests) {
        for (const [asset, routes] of readReferences(manifest)) {
            if (!routesByAsset.has(asset)) {
                routesByAsset.set(asset, new Set());
            }
            for (const route of routes) {
                routesByAsset.get(asset).add(route);
            }
        }
    }

    const missing = [...routesByAsset.keys()]
        .filter((asset) => !existsSync(emittedPath(asset, root)))
        .sort();

    if (missing.length > 0) {
        console.error(
            `${missing.length} asset(s) referenced by a route manifest were never emitted by the build:`,
        );
        for (const asset of missing) {
            const routes = [...routesByAsset.get(asset)].sort();
            const shown = routes.slice(0, ROUTES_SHOWN).join(", ");
            const rest = routes.length > ROUTES_SHOWN
                ? ` and ${routes.length - ROUTES_SHOWN} more route(s)`
                : "";
            console.error(`  ${asset}\n    expected at ${relative(root, emittedPath(asset, root))}\n    required by ${shown}${rest}`);
        }
        return 1;
    }

    console.log(
        `Verified ${routesByAsset.size} asset reference(s) across ${manifests.length} route manifest(s) in ${root}.`,
    );
    return 0;
}

process.exit(verify(process.argv[2] ?? ".next"));
