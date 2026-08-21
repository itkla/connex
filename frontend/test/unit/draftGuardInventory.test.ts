import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { act, createElement } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { useUnsavedChangesGuard } from "@/app/hooks/useUnsavedChangesGuard";

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

function installMinimalDocument() {
    class HtmlIFrameElement {}

    const documentTarget = {
        nodeType: 9,
        activeElement: null,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        createElement: vi.fn(() => containerTarget),
        createElementNS: vi.fn(() => containerTarget),
        createTextNode: vi.fn((value: string) => ({
            nodeType: 3,
            nodeName: "#text",
            nodeValue: value,
            parentNode: null,
            ownerDocument: documentTarget,
        })),
        getElementById: vi.fn(() => null),
    };
    const windowTarget = {
        document: documentTarget,
        event: undefined,
        HTMLIFrameElement: HtmlIFrameElement,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        setTimeout: vi.fn(() => 1),
        clearTimeout: vi.fn(),
    };
    const containerTarget = {
        nodeType: 1,
        tagName: "DIV",
        nodeName: "DIV",
        namespaceURI: "http://www.w3.org/1999/xhtml",
        ownerDocument: documentTarget,
        firstChild: null,
        lastChild: null,
        parentNode: null,
        textContent: "",
        style: {
            setProperty: vi.fn(),
            removeProperty: vi.fn(() => ""),
            getPropertyValue: vi.fn(() => ""),
        },
        getBoundingClientRect: vi.fn(() => ({
            x: 0,
            y: 0,
            top: 0,
            right: 0,
            bottom: 0,
            left: 0,
            width: 0,
            height: 0,
        })),
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        appendChild: vi.fn(),
        insertBefore: vi.fn(),
        removeChild: vi.fn(),
        setAttribute: vi.fn(),
        removeAttribute: vi.fn(),
    };
    Object.assign(documentTarget, {
        defaultView: windowTarget,
        documentElement: containerTarget,
        body: containerTarget,
    });
    vi.stubGlobal("window", windowTarget);
    vi.stubGlobal("self", windowTarget);
    vi.stubGlobal("document", documentTarget);
    vi.stubGlobal("requestAnimationFrame", vi.fn((callback: FrameRequestCallback) => {
        callback(0);
        return 1;
    }));
    vi.stubGlobal("cancelAnimationFrame", vi.fn());
    vi.stubGlobal("getComputedStyle", vi.fn(() => ({ getPropertyValue: () => "" })));
    vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true);
    return document.createElement("div");
}

afterEach(() => {
    vi.unstubAllGlobals();
});

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

    it("routes every owning surface's outside dismissal and confirm actions through the guard", () => {
        const missing = inventory.surfaces
            .filter((entry) => entry.guard === OWN_GUARD)
            .flatMap((entry) => {
                const source = readSource(entry.file);
                const gaps: string[] = [];
                const overlayRoutesDismissal = source.includes("onOpenChange={guard.onOpenChange}")
                    || (source.includes("onOpenChange={handleOpenChange}") && source.includes("guard.onOpenChange("));
                if (!overlayRoutesDismissal) gaps.push(`${entry.file} does not route outside dismissal through the guard`);
                if (!source.includes("open={guard.confirm.open}")) gaps.push(`${entry.file} does not expose guard confirm state`);
                if (!source.includes("onKeepEditing={guard.confirm.onKeepEditing}")) {
                    gaps.push(`${entry.file} does not wire the keep-editing action`);
                }
                if (!source.includes("guard.confirm.onDiscard")) gaps.push(`${entry.file} does not wire the discard action`);
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

describe.each(inventory.surfaces)("$surface draft guard", (entry) => {
    it("survives outside dismissal until discard is confirmed", async () => {
        const container = installMinimalDocument();
        const { createRoot } = await import("react-dom/client");
        const root = createRoot(container, { onCaughtError: vi.fn() });
        const onClose = vi.fn();
        let observed: ReturnType<typeof useUnsavedChangesGuard> | null = null;

        function GuardProbe() {
            observed = useUnsavedChangesGuard({ isDirty: true, onClose });
            return null;
        }

        function currentGuard(): ReturnType<typeof useUnsavedChangesGuard> {
            if (observed === null) throw new Error(`${entry.surface} guard did not mount`);
            return observed;
        }

        await act(async () => {
            root.render(createElement(GuardProbe));
        });

        await act(async () => {
            currentGuard().onOpenChange(false);
        });
        expect(onClose, `${entry.surface} closed on outside dismissal`).not.toHaveBeenCalled();
        expect(currentGuard().confirm.open, `${entry.surface} did not ask before discarding`).toBe(true);

        await act(async () => {
            currentGuard().confirm.onKeepEditing();
        });
        expect(onClose, `${entry.surface} closed after keeping edits`).not.toHaveBeenCalled();
        expect(currentGuard().confirm.open).toBe(false);

        await act(async () => {
            currentGuard().onOpenChange(false);
        });
        await act(async () => {
            currentGuard().confirm.onDiscard();
        });
        expect(onClose, `${entry.surface} did not close after confirmed discard`).toHaveBeenCalledOnce();

        await act(async () => root.unmount());
    });
});
