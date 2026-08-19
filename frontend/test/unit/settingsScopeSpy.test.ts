import { describe, expect, it } from "vitest";

import { topmostIntersecting, type ObservedSection } from "@/app/lib/settingsScopeSpy";

/**
 * Gate over the settings scope spine's scroll-spy (#1340 WS4.1).
 *
 * `IntersectionObserver` delivers only the sections whose intersection changed, so the two failure
 * modes below are what the naive "reduce over the callback's entries" version produces, in opposite
 * scroll directions. Both are replayed here as observation sequences rather than described, because
 * neither is visible without scrolling a real page.
 */
function replay(sequence: readonly (readonly ObservedSection[])[]): (string | null)[] {
    const observed = new Map<string, ObservedSection>();
    const highlights: (string | null)[] = [];
    for (const batch of sequence) {
        for (const section of batch) observed.set(section.id, section);
        highlights.push(topmostIntersecting([...observed.values()]));
    }
    return highlights;
}

describe("the scope spine follows the section the reader is in", () => {
    it("picks the highest section currently in the band", () => {
        expect(
            topmostIntersecting([
                { id: "personal", isIntersecting: true, top: 120 },
                { id: "workspace", isIntersecting: true, top: 460 },
            ]),
        ).toBe("personal");
    });

    it("ignores a section that has left the band", () => {
        expect(
            topmostIntersecting([
                { id: "personal", isIntersecting: false, top: -320 },
                { id: "workspace", isIntersecting: true, top: 40 },
            ]),
        ).toBe("workspace");
    });

    it("stays on the section still topping the band when the next one only enters it", () => {
        const highlights = replay([
            [{ id: "personal", isIntersecting: true, top: 100 }],
            [{ id: "workspace", isIntersecting: true, top: 520 }],
        ]);

        expect(
            highlights,
            "reducing over the changed entries alone would jump to workspace the moment it appears",
        ).toEqual(["personal", "personal"]);
    });

    it("follows the reader back up when the last section's exit is the only change", () => {
        const highlights = replay([
            [
                { id: "personal", isIntersecting: false, top: -900 },
                { id: "workspace", isIntersecting: false, top: -400 },
                { id: "organization", isIntersecting: true, top: 60 },
            ],
            [{ id: "organization", isIntersecting: false, top: 900 }],
            [{ id: "workspace", isIntersecting: true, top: 300 }],
            [{ id: "personal", isIntersecting: true, top: 80 }],
        ]);

        expect(
            highlights,
            "bailing out on an empty visible set would strand the spine on organization forever",
        ).toEqual(["organization", null, "workspace", "personal"]);
    });

    it("reports nothing when no section is in the band", () => {
        expect(topmostIntersecting([{ id: "personal", isIntersecting: false, top: 900 }])).toBeNull();
        expect(topmostIntersecting([])).toBeNull();
    });
});
