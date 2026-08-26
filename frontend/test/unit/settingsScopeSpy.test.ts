import { describe, expect, it } from "vitest";

import { activeScopeSection } from "@/app/lib/settingsScopeSpy";

/**
 * Gate over the settings scope spine's scroll-spy (#1340 WS4.1).
 *
 * `IntersectionObserver` delivers only the sections whose intersection changed, so the two failure
 * modes below are what the naive "reduce over the callback's entries" version produces, in opposite
 * scroll directions. Both are replayed here as observation sequences rather than described, because
 * neither is visible without scrolling a real page.
 */
const ORDER = ["settings-scope-personal", "settings-scope-workspace", "settings-scope-organization"];

/** Replays a run of observer callbacks and reports what the spine highlighted after each. */
function replay(batches: readonly (readonly (readonly [string, boolean])[])[]): (string | null)[] {
    const intersecting = new Set<string>();
    return batches.map((batch) => {
        for (const [id, isIntersecting] of batch) {
            if (isIntersecting) intersecting.add(id);
            else intersecting.delete(id);
        }
        return activeScopeSection(ORDER, intersecting);
    });
}

describe("the scope spine follows the section the reader is in", () => {
    it("picks the first section in document order that is in the band", () => {
        expect(activeScopeSection(ORDER, new Set(ORDER))).toBe("settings-scope-personal");
        expect(
            activeScopeSection(ORDER, new Set(["settings-scope-organization", "settings-scope-workspace"])),
        ).toBe("settings-scope-workspace");
    });

    it("ignores a section that has left the band", () => {
        expect(activeScopeSection(ORDER, new Set(["settings-scope-organization"]))).toBe(
            "settings-scope-organization",
        );
    });

    it("stays on the section still topping the band when the next one only enters it", () => {
        const highlights = replay([
            [["settings-scope-personal", true]],
            [["settings-scope-workspace", true]],
        ]);

        expect(
            highlights,
            "reducing over the changed entries alone would jump to workspace the moment it appears",
        ).toEqual(["settings-scope-personal", "settings-scope-personal"]);
    });

    it("follows the reader back up when the last section's exit is the only change", () => {
        const highlights = replay([
            [
                ["settings-scope-personal", false],
                ["settings-scope-workspace", false],
                ["settings-scope-organization", true],
            ],
            [["settings-scope-organization", false]],
            [["settings-scope-workspace", true]],
            [["settings-scope-personal", true]],
        ]);

        expect(
            highlights,
            "bailing out on an empty visible set would strand the spine on organization forever",
        ).toEqual([
            "settings-scope-organization",
            null,
            "settings-scope-workspace",
            "settings-scope-personal",
        ]);
    });

    it("reports nothing when no section is in the band", () => {
        expect(activeScopeSection(ORDER, new Set())).toBeNull();
        expect(activeScopeSection([], new Set(["settings-scope-personal"]))).toBeNull();
    });

    it("never answers with a section the caller did not list", () => {
        expect(activeScopeSection(ORDER, new Set(["settings-scope-unknown"]))).toBeNull();
    });
});
