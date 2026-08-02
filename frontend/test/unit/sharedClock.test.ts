import { readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const APP_ROOT = path.resolve(process.cwd(), "app");

function read(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), "utf8");
}

function sourceFiles(directory: string): string[] {
    const found: string[] = [];
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
        const full = path.join(directory, entry.name);
        if (entry.isDirectory()) found.push(...sourceFiles(full));
        else if (entry.name.endsWith(".ts") || entry.name.endsWith(".tsx")) found.push(full);
    }
    return found;
}

const HOOK_IMPORT = /import \{ (useNow|useLiveNow) \} from ["']@\/app\/hooks\/useNow["']/;

const clockConsumers = sourceFiles(APP_ROOT)
    .filter((file) => file !== path.join(APP_ROOT, "hooks", "useNow.tsx"))
    .filter((file) => HOOK_IMPORT.test(readFileSync(file, "utf8")));

describe("shared render clock", () => {
    it("is mounted by the app shell, so no consumer can be rendered without a provider", () => {
        const layout = read("app/(app)/layout.tsx");

        expect(layout).toMatch(/import \{ NowProvider \} from "@\/app\/hooks\/useNow";/);
        expect(layout).toMatch(/<NowProvider value=\{requestNow\(\)\}>/);
    });

    it("is read only by client components", () => {
        expect(clockConsumers.length).toBeGreaterThan(0);

        const serverConsumers = clockConsumers.filter((file) => {
            const source = readFileSync(file, "utf8");
            return !source.startsWith("'use client'") && !source.startsWith('"use client"');
        });

        expect(serverConsumers.map((file) => path.relative(process.cwd(), file))).toEqual([]);
    });
});
