import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

/**
 * Gate over the committed draft-guard denominator. It proves that every surface **named in**
 * `lint/draft-guard-inventory.json` wires `useUnsavedChangesGuard` + `ConfirmDiscardDialog`, itself or
 * through a component it renders.
 *
 * It is list-driven, and that is its one blind spot: a new dialog or drawer that accumulates input and
 * is never added to the inventory is invisible here and this suite still passes. The inventory is the
 * denominator only because adding the surface to it is part of adding the surface — nothing in this
 * file can discover an unlisted one.
 */
const INVENTORY_PATH = path.join(process.cwd(), "lint", "draft-guard-inventory.json");

const GUARD_HOOK = "useUnsavedChangesGuard";
const CONFIRM_DIALOG = "ConfirmDiscardDialog";
const OWN_GUARD = "own";
const MAX_DELEGATION_DEPTH = 8;

type DraftGuardSurface = {
    file: string;
    surface: string;
    guard: string;
};

type DraftGuardInventory = {
    minimumCount: number;
    surfaces: DraftGuardSurface[];
};

function isJsonObject(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function readInventory(): DraftGuardInventory {
    const parsed: unknown = JSON.parse(readFileSync(INVENTORY_PATH, "utf8"));
    if (!isJsonObject(parsed)) throw new Error("draft-guard-inventory.json is not an object");
    const { minimumCount, surfaces } = parsed;
    if (typeof minimumCount !== "number") throw new Error("minimumCount must be a number");
    if (!Array.isArray(surfaces)) throw new Error("surfaces must be an array");
    const entries = surfaces.map((entry) => {
        if (!isJsonObject(entry)) throw new Error("every surface must be an object");
        const { file, surface, guard } = entry;
        if (typeof file !== "string") throw new Error("every surface needs a file");
        if (typeof surface !== "string") throw new Error(`every surface needs a name: ${file}`);
        if (typeof guard !== "string") throw new Error(`every surface needs a guard: ${file}`);
        return { file, surface, guard } satisfies DraftGuardSurface;
    });
    return { minimumCount, surfaces: entries };
}

function readSource(file: string): string {
    return readFileSync(path.join(process.cwd(), file), "utf8");
}

/** The `@/`-aliased specifier a file is imported by, extension dropped as the imports write it. */
function importSpecifier(file: string): string {
    return `@/${file.replace(/\.tsx?$/, "")}`;
}

const inventory = readInventory();
const byFile = new Map(inventory.surfaces.map((entry) => [entry.file, entry]));

/** Follows `guard` references to the file that actually wires the guard, or null if it does not resolve. */
function resolveOwner(entry: DraftGuardSurface): DraftGuardSurface | null {
    let current = entry;
    for (let depth = 0; depth < MAX_DELEGATION_DEPTH; depth += 1) {
        if (current.guard === OWN_GUARD) return current;
        const next = byFile.get(current.guard);
        if (!next) return null;
        current = next;
    }
    return null;
}

describe("draft-guard inventory", () => {
    it("names files that exist, uniquely and in sorted order", () => {
        const files = inventory.surfaces.map((entry) => entry.file);

        expect(files).toEqual([...new Set(files)].sort());
        expect(files.filter((file) => !existsSync(path.join(process.cwd(), file)))).toEqual([]);
    });

    it("only ever grows", () => {
        expect(
            inventory.surfaces.length,
            "a surface leaves the inventory only when it stops accumulating input; lower minimumCount in that same commit",
        ).toBeGreaterThanOrEqual(inventory.minimumCount);
    });

    it("wires the guard and the confirm on every surface that owns one", () => {
        const missing = inventory.surfaces
            .filter((entry) => entry.guard === OWN_GUARD)
            .flatMap((entry) => {
                const source = readSource(entry.file);
                const gaps: string[] = [];
                if (!source.includes(GUARD_HOOK)) gaps.push(`${entry.file} does not use ${GUARD_HOOK}`);
                if (!source.includes(`<${CONFIRM_DIALOG}`)) gaps.push(`${entry.file} does not render ${CONFIRM_DIALOG}`);
                return gaps;
            });

        expect(missing).toEqual([]);
    });

    it("resolves every delegating surface to a file that renders the guarded shell it names", () => {
        const broken = inventory.surfaces
            .filter((entry) => entry.guard !== OWN_GUARD)
            .flatMap((entry) => {
                const gaps: string[] = [];
                if (resolveOwner(entry) === null) {
                    gaps.push(`${entry.file} delegates to ${entry.guard}, which is not an inventory surface that owns a guard`);
                    return gaps;
                }
                if (!readSource(entry.file).includes(importSpecifier(entry.guard))) {
                    gaps.push(`${entry.file} does not import ${entry.guard}`);
                }
                return gaps;
            });

        expect(broken).toEqual([]);
    });

    it("covers the surfaces #1344 committed as the acceptance denominator", () => {
        const required = [
            "app/components/activity/activities/ActivityDialog.tsx",
            "app/components/activity/tasks/TaskDialog.tsx",
            "app/components/marketing/campaigns/EditCampaignSheet.tsx",
            "app/components/marketing/campaigns/NewCampaignDialog.tsx",
            "app/components/records/quick-edit/QuickEditSheetShell.tsx",
            "app/components/reports/GoalDialog.tsx",
            "app/components/reports/ScheduleDialog.tsx",
        ];

        expect(required.filter((file) => !byFile.has(file))).toEqual([]);
    });
});
