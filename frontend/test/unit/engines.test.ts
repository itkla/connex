import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const LOCKED_TOOLCHAIN_ENGINES = {
    rolldown: "^20.19.0 || >=22.12.0",
    vite: "^20.19.0 || >=22.12.0",
    vitest: "^20.0.0 || ^22.0.0 || >=24.0.0",
} as const;

const DECLARED_NODE_RANGE = "^20.19.0 || ^22.12.0 || >=24.0.0";

const lockfile = readFileSync(new URL("../../pnpm-lock.yaml", import.meta.url), "utf8");
const manifest = JSON.parse(readFileSync(new URL("../../package.json", import.meta.url), "utf8")) as {
    engines?: Record<string, string>;
};

/**
 * The `packages:` block of the lockfile, which is the only section carrying `engines` metadata —
 * `snapshots:` repeats the same identifiers with peer suffixes and no engine ranges.
 */
function packagesSection(): string {
    const start = lockfile.indexOf("\npackages:\n");
    const end = lockfile.indexOf("\nsnapshots:\n");
    if (start < 0 || end < 0 || end < start) {
        throw new Error("pnpm-lock.yaml has no packages:/snapshots: sections — the parser below is stale");
    }
    return lockfile.slice(start, end);
}

/**
 * Node engine ranges recorded in the lockfile for the named packages, keyed by package name.
 * A package resolved to more than one version, or to none, is reported as such so the assertion
 * fails loudly instead of silently comparing nothing.
 */
function lockedNodeEngines(names: readonly string[]): Record<string, string> {
    const engines: Record<string, string> = {};
    const seen = new Set<string>();
    let current: string | null = null;

    for (const line of packagesSection().split("\n")) {
        if (/^ {2}\S/.test(line)) {
            const name = /^ {2}(.+)@[^@\s]+:$/.exec(line)?.[1] ?? null;
            current = name !== null && names.includes(name) ? name : null;
            if (current !== null) {
                if (seen.has(current)) {
                    engines[current] = "resolved to multiple versions";
                }
                seen.add(current);
            }
            continue;
        }
        if (current === null) {
            continue;
        }
        const node = /^ {4}engines: \{(.+)\}$/
            .exec(line)?.[1]
            .split(", ")
            .find((field) => field.startsWith("node: "));
        if (node !== undefined && engines[current] === undefined) {
            engines[current] = node.slice("node: ".length).replace(/^'(.*)'$/, "$1");
        }
    }

    for (const name of names) {
        engines[name] ??= "no engines entry in pnpm-lock.yaml";
    }
    return engines;
}

describe("frontend Node engine floor", () => {
    it("matches the locked Vite/rolldown/Vitest ranges it was derived from", () => {
        expect(lockedNodeEngines(Object.keys(LOCKED_TOOLCHAIN_ENGINES))).toEqual({ ...LOCKED_TOOLCHAIN_ENGINES });
    });

    it("is declared verbatim in package.json", () => {
        expect(manifest.engines?.node).toBe(DECLARED_NODE_RANGE);
    });
});
