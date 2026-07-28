import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { describe, expect, it } from "vitest";

const LOCKED_TOOLCHAIN_ENGINES = {
    "eslint-visitor-keys@5.0.1": "^20.19.0 || ^22.13.0 || >=24",
    "rolldown@1.1.5": "^20.19.0 || >=22.12.0",
    "vite@8.1.5": "^20.19.0 || >=22.12.0",
    "vitest@4.1.10": "^20.0.0 || ^22.0.0 || >=24.0.0",
} as const;

const PINNED_PACKAGE_MANAGER = {
    id: "pnpm@11.9.0",
    nodeRange: ">=22.13",
} as const;

const DECLARED_NODE_RANGE = "^22.13.0 || >=24.0.0";

const rootAgentGuide = readFileSync(new URL("../../../AGENTS.md", import.meta.url), "utf8");
const frontendTestingGuide = readFileSync(new URL("../../../docs/FRONTEND_TESTING.md", import.meta.url), "utf8");
const frontendRequire = createRequire(import.meta.url);

type Version = readonly [major: number, minor: number, patch: number];

type VersionInterval = {
    min: Version;
    maxExclusive: Version | null;
};

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function frontendManifest(): Readonly<{ nodeRange: string; packageManager: string }> {
    const parsed: unknown = JSON.parse(readFileSync(new URL("../../package.json", import.meta.url), "utf8"));
    if (
        !isRecord(parsed)
        || typeof parsed.packageManager !== "string"
        || !isRecord(parsed.engines)
        || typeof parsed.engines.node !== "string"
    ) {
        throw new Error("package.json must declare packageManager and engines.node as strings");
    }
    return {
        nodeRange: parsed.engines.node,
        packageManager: parsed.packageManager,
    };
}

function packageEngine(manifestPath: string): readonly [packageId: string, nodeRange: string] {
    const parsed: unknown = JSON.parse(readFileSync(manifestPath, "utf8"));
    if (
        !isRecord(parsed)
        || typeof parsed.name !== "string"
        || typeof parsed.version !== "string"
        || !isRecord(parsed.engines)
        || typeof parsed.engines.node !== "string"
    ) {
        throw new Error(`${manifestPath} must declare name, version, and engines.node as strings`);
    }
    return [`${parsed.name}@${parsed.version}`, parsed.engines.node];
}

function loadedToolchainEngines(): Record<string, string> {
    const vitestManifest = frontendRequire.resolve("vitest/package.json");
    const vitestRequire = createRequire(vitestManifest);
    const viteManifest = vitestRequire.resolve("vite/package.json");
    const viteRequire = createRequire(viteManifest);
    const rolldownManifest = viteRequire.resolve("rolldown/package.json");

    const nextTypescriptConfig = frontendRequire.resolve("eslint-config-next/typescript");
    const nextTypescriptRequire = createRequire(nextTypescriptConfig);
    const typescriptEslintManifest = nextTypescriptRequire.resolve("typescript-eslint/package.json");
    const typescriptEslintRequire = createRequire(typescriptEslintManifest);
    const typescriptPluginManifest = typescriptEslintRequire.resolve("@typescript-eslint/eslint-plugin/package.json");
    const typescriptPluginRequire = createRequire(typescriptPluginManifest);
    const typescriptVisitorManifest = typescriptPluginRequire.resolve("@typescript-eslint/visitor-keys/package.json");
    const typescriptVisitorRequire = createRequire(typescriptVisitorManifest);
    const eslintVisitorManifest = typescriptVisitorRequire.resolve("eslint-visitor-keys/package.json");

    return Object.fromEntries([
        packageEngine(eslintVisitorManifest),
        packageEngine(rolldownManifest),
        packageEngine(viteManifest),
        packageEngine(vitestManifest),
    ]);
}

function parseVersion(value: string): Version {
    const match = /^(\d+)(?:\.(\d+))?(?:\.(\d+))?$/.exec(value);
    if (match === null) {
        throw new Error(`Unsupported Node version: ${value}`);
    }
    return [Number(match[1]), Number(match[2] ?? 0), Number(match[3] ?? 0)];
}

function parseInterval(value: string): VersionInterval {
    if (value.startsWith("^")) {
        const min = parseVersion(value.slice(1));
        return {
            min,
            maxExclusive: [min[0] + 1, 0, 0],
        };
    }
    if (value.startsWith(">=")) {
        return {
            min: parseVersion(value.slice(2)),
            maxExclusive: null,
        };
    }
    throw new Error(`Unsupported Node range member: ${value}`);
}

function parseRange(value: string): VersionInterval[] {
    return value.split(" || ").map(parseInterval);
}

function compareVersions(left: Version, right: Version): number {
    for (let index = 0; index < left.length; index += 1) {
        const difference = left[index] - right[index];
        if (difference !== 0) {
            return difference;
        }
    }
    return 0;
}

function intervalContains(superset: VersionInterval, subset: VersionInterval): boolean {
    if (compareVersions(superset.min, subset.min) > 0) {
        return false;
    }
    if (superset.maxExclusive === null) {
        return true;
    }
    return subset.maxExclusive !== null
        && compareVersions(superset.maxExclusive, subset.maxExclusive) >= 0;
}

function rangeContains(superset: string, subset: string): boolean {
    const supersetIntervals = parseRange(superset);
    return parseRange(subset).every((subsetInterval) =>
        supersetIntervals.some((supersetInterval) => intervalContains(supersetInterval, subsetInterval)),
    );
}

describe("frontend Node engine floor", () => {
    it("matches the locked toolchain ranges it was derived from", () => {
        expect(loadedToolchainEngines()).toEqual({ ...LOCKED_TOOLCHAIN_ENGINES });
    });

    it("is declared verbatim in package.json", () => {
        expect(frontendManifest()).toEqual({
            nodeRange: DECLARED_NODE_RANGE,
            packageManager: PINNED_PACKAGE_MANAGER.id,
        });
    });

    it("is documented in the repository guides", () => {
        expect(rootAgentGuide).toContain(`Node \`${DECLARED_NODE_RANGE}\``);
        expect(frontendTestingGuide).toContain(`**Node floor:** \`${DECLARED_NODE_RANGE}\``);
    });

    it("stays within every locked toolchain range", () => {
        const requiredRanges = [
            PINNED_PACKAGE_MANAGER.nodeRange,
            ...Object.values(LOCKED_TOOLCHAIN_ENGINES),
        ];
        for (const range of requiredRanges) {
            expect(rangeContains(range, DECLARED_NODE_RANGE)).toBe(true);
        }
    });

    it("rejects a toolchain range that narrows any declared Node line", () => {
        expect(rangeContains("^22.14.0 || >=24.0.0", DECLARED_NODE_RANGE)).toBe(false);
        expect(rangeContains("^22.13.0 || >=25.0.0", DECLARED_NODE_RANGE)).toBe(false);
    });
});
