import { existsSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

import {
    EXCLUSION_BASELINE,
    RAW_ERROR_TOAST_EXCLUSIONS,
    SONNER_ALLOWED,
    SONNER_IMPORT_EXCLUSIONS,
} from "@/lint/errorVoice.mjs";

const inventories: [string, string[], number][] = [
    ["sonner imports", SONNER_IMPORT_EXCLUSIONS, EXCLUSION_BASELINE.sonnerImports],
    ["raw error toasts", RAW_ERROR_TOAST_EXCLUSIONS, EXCLUSION_BASELINE.rawErrorToasts],
];

describe.each(inventories)("%s exclusion inventory", (_name, exclusions, baseline) => {
    it("matches the committed ledger exactly", () => {
        expect(exclusions.length).toBe(baseline);
    });

    it("names each file once, in order", () => {
        expect(exclusions).toEqual([...new Set(exclusions)].sort());
    });

    it("names only files that still exist", () => {
        expect(exclusions.filter((path) => !existsSync(join(process.cwd(), path)))).toEqual([]);
    });
});

describe("sonner allowlist", () => {
    it("covers only the branded helpers and the toaster they render through", () => {
        expect(SONNER_ALLOWED).toEqual(["app/lib/toast.ts", "components/ui/sonner.tsx"]);
    });

    it("never overlaps the shrinking inventory", () => {
        expect(SONNER_ALLOWED.filter((path) => SONNER_IMPORT_EXCLUSIONS.includes(path))).toEqual([]);
    });
});
