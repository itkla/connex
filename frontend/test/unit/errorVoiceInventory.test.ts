import { existsSync, globSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { Linter } from "eslint";
import nextTs from "eslint-config-next/typescript";
import { describe, expect, it } from "vitest";

import {
    EXCLUSION_BASELINE,
    RAW_ERROR_TOAST_EXCLUSIONS,
    SONNER_ALLOWED,
    SONNER_IMPORT_EXCLUSIONS,
    errorVoiceProbeConfig,
} from "@/lint/errorVoice.mjs";

const sourceFiles = globSync([
    "app/**/*.{ts,tsx,js,jsx,mjs}",
    "components/**/*.{ts,tsx,js,jsx,mjs}",
], { cwd: process.cwd() }).sort();
const parserConfig = nextTs[0];
if (parserConfig === undefined) throw new Error("Next.js TypeScript parser config is missing");

const linter = new Linter({ configType: "flat" });
const liveSonnerImports: string[] = [];
const liveRawErrorToasts: string[] = [];
for (const file of sourceFiles) {
    const messages = linter.verify(
        readFileSync(join(process.cwd(), file), "utf8"),
        [parserConfig, ...errorVoiceProbeConfig()],
        { filename: file },
    );
    if (messages.some((message) => message.ruleId === "no-restricted-imports")) {
        liveSonnerImports.push(file);
    }
    if (messages.some((message) => message.ruleId === "no-restricted-syntax")) {
        liveRawErrorToasts.push(file);
    }
}

const liveSonnerExclusions = liveSonnerImports.filter((file) => !SONNER_ALLOWED.includes(file));
const inventories: [string, string[], number, string[]][] = [
    ["sonner imports", SONNER_IMPORT_EXCLUSIONS, EXCLUSION_BASELINE.sonnerImports, liveSonnerExclusions],
    ["raw error toasts", RAW_ERROR_TOAST_EXCLUSIONS, EXCLUSION_BASELINE.rawErrorToasts, liveRawErrorToasts],
];

describe.each(inventories)("%s exclusion inventory", (_name, exclusions, baseline, liveViolations) => {
    it("matches the committed ledger exactly", () => {
        expect(exclusions.length).toBe(baseline);
    });

    it("names each file once, in order", () => {
        expect(exclusions).toEqual([...new Set(exclusions)].sort());
    });

    it("names only files that still exist", () => {
        expect(exclusions.filter((path) => !existsSync(join(process.cwd(), path)))).toEqual([]);
    });

    it("matches the violations derived from the shipping lint rules", () => {
        expect(exclusions).toEqual(liveViolations);
    });
});

describe("sonner allowlist", () => {
    it("covers only the branded helpers and the toaster they render through", () => {
        expect(SONNER_ALLOWED).toEqual(["app/lib/toast.ts", "components/ui/sonner.tsx"]);
        expect(liveSonnerImports).toEqual(SONNER_ALLOWED);
    });

    it("never overlaps the shrinking inventory", () => {
        expect(SONNER_ALLOWED.filter((path) => SONNER_IMPORT_EXCLUSIONS.includes(path))).toEqual([]);
    });
});
