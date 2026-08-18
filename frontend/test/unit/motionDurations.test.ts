import { existsSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

import {
    BASELINE_HIGH_WATER_MARK,
    loadBaseline,
    scanFile,
    scanMotionDurations,
    TOKENIZED_SURFACES,
} from "@/lint/motionDurations.mjs";

const found = scanMotionDurations();
const baseline: Record<string, number> = loadBaseline();

const BURN_DOWN =
    "The ledger only shrinks: put the surface on --motion-micro / --motion-standard / --motion-expressive " +
    "(or their app/lib/motion.ts mirrors) and lower or delete its entry in frontend/lint/motion-duration-baseline.json.";

describe("motion timing tokens", () => {
    it("finds no hard-coded duration in a file outside the committed ledger", () => {
        const added = Object.keys(found)
            .filter((file) => !(file in baseline))
            .map((file) => `${file} (${scanFile(file).join(", ")})`);

        expect(added, `${added.length} file(s) hard-code a timing. ${BURN_DOWN}`).toEqual([]);
    });

    it("holds no ledger entry that grew", () => {
        const grown = Object.entries(baseline)
            .filter(([file, allowed]) => (found[file] ?? 0) > allowed)
            .map(([file, allowed]) => `${file}: ${allowed} → ${found[file]}`);

        expect(grown, `${grown.length} file(s) added a hard-coded timing. ${BURN_DOWN}`).toEqual([]);
    });

    it("holds no ledger entry that is already clean", () => {
        const stale = Object.keys(baseline).filter((file) => (found[file] ?? 0) === 0);

        expect(stale, `${stale.length} ledger entry(s) are fixed. ${BURN_DOWN}`).toEqual([]);
    });

    it("keeps the ledger sorted and scoped to files that still exist", () => {
        const files = Object.keys(baseline);

        expect(files).toEqual([...files].sort());
        expect(files.filter((file) => !existsSync(join(process.cwd(), file)))).toEqual([]);
    });

    it("never grows past the committed high-water mark", () => {
        const total = Object.values(baseline).reduce((sum, count) => sum + count, 0);

        expect(total).toBeLessThanOrEqual(BASELINE_HIGH_WATER_MARK);
    });
});

describe("tokenized surfaces", () => {
    it("carries no hard-coded timing on any shared primitive the motion system owns", () => {
        const regressed = TOKENIZED_SURFACES.filter((file) => (found[file] ?? 0) > 0).map(
            (file) => `${file} (${scanFile(file).join(", ")})`
        );

        expect(
            regressed,
            `${regressed.length} tokenized primitive(s) reintroduced a raw timing. These carry the motion system for every overlay, menu, and press in the product — use the tokens.`
        ).toEqual([]);
    });

    it("never overlaps the shrinking ledger", () => {
        expect(TOKENIZED_SURFACES.filter((file) => file in baseline)).toEqual([]);
    });

    it("names each primitive once, in order, and only if it exists", () => {
        expect(TOKENIZED_SURFACES).toEqual([...new Set(TOKENIZED_SURFACES)].sort());
        expect(TOKENIZED_SURFACES.filter((file) => !existsSync(join(process.cwd(), file)))).toEqual([]);
    });
});
