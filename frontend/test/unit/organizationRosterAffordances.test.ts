import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

/**
 * Gate over the organization roster's write affordances (#1340 PR 6).
 *
 * The manifest records `orgWrite: "owner"` on the administrator roster, and every mutation behind it
 * — adding an administrator, changing a role, removing one — is `requireOrgOwner` on the backend.
 * §6's rule is to prefer no entry point over a locked door, so an organization administrator who is
 * not an owner must be offered none of the three.
 *
 * **What this suite is, and what it is not.** It reads structure, not strings: each affordance is
 * located inside the brace-matched extent of the guard that controls it, so the check fails if a
 * refactor lifts one out of its guard — the exact regression a `toContain("const isOwner = …")` pin
 * sails through. The mutation cases below prove that by running the same analysis over a source with
 * each guard removed and requiring it to flag them.
 *
 * It is still static analysis, and it cannot see what React actually renders. A mounted both-roles
 * render is the stronger test and was attempted; `OrgMembersPanel` does not mount under this repo's
 * synthetic-DOM harness (`installInteractiveDocument`) — it hangs even with an empty roster, where
 * `OrgAllowedDomainsPanel` mounts and asserts cleanly on the same harness. Rather than keep bending
 * the harness, the behavioral half of this claim is carried by the browser pass, which rendered both
 * roles against a real backend and recorded the affordance sets directly: an owner saw the role
 * select, the row menu and the add form; an administrator saw none of the three and kept the
 * allowed-domain and single-sign-on controls, which are `requireOrgAdmin` and must stay.
 */
const PANEL = path.join(
    process.cwd(),
    "app",
    "components",
    "organization",
    "OrgMembersPanel.tsx",
);

function source(): string {
    return readFileSync(PANEL, "utf8");
}

/** One owner-only control, and the guard the panel is supposed to keep it behind. */
type Affordance = {
    name: string;
    /** A marker unique to the control's own markup. */
    marker: string;
    /** The opening of the JSX conditional that must contain it. */
    guard: string;
};

const AFFORDANCES: readonly Affordance[] = [
    { name: "change role", marker: 'aria-label={t("changeRole")}', guard: "{editable ? (" },
    { name: "remove administrator", marker: 'aria-label={t("remove")}', guard: "{removable && (" },
    { name: "add administrator", marker: 'aria-label={t("addEmailLabel")}', guard: "{isOwner && (" },
];

/**
 * The source between a guard's opening brace and its match.
 *
 * Brace matching rather than a line window: the guarded blocks here are dozens of lines long and
 * nested, so anything shorter would either miss the control or reach past the guard and call an
 * unguarded control guarded.
 */
function guardedExtent(text: string, guard: string): string | null {
    const start = text.indexOf(guard);
    if (start === -1) return null;
    let depth = 0;
    for (let index = start; index < text.length; index += 1) {
        const character = text[index];
        if (character === "{") depth += 1;
        else if (character === "}") {
            depth -= 1;
            if (depth === 0) return text.slice(start, index + 1);
        }
    }
    return null;
}

/** The owner-only controls the panel would offer to an organization administrator. */
function unguardedAffordances(text: string): readonly string[] {
    return AFFORDANCES.filter((affordance) => {
        if (!text.includes(affordance.marker)) return false;
        const extent = guardedExtent(text, affordance.guard);
        return extent === null || !extent.includes(affordance.marker);
    }).map((affordance) => affordance.name);
}

describe("the administrator roster keeps its owner-only controls behind an owner guard", () => {
    it("derives every guard from the viewer's own organization role", () => {
        const text = source();

        expect(
            text,
            "the roster's guards are only as good as what they are computed from",
        ).toContain('const isOwner = activeWorkspace?.orgRole === "owner";');
        expect(text).toContain("const editable = isOwner && !lockedSoleOwner;");
        expect(text).toContain("const removable = isOwner && !lockedSoleOwner;");
    });

    it("renders each of the three owner-only controls inside its guard", () => {
        const text = source();

        for (const affordance of AFFORDANCES) {
            expect(text, `${affordance.name} left the panel`).toContain(affordance.marker);
        }
        expect(
            unguardedAffordances(text),
            "an organization administrator holds none of these on the backend, so offering one is a locked door",
        ).toEqual([]);
    });

    it.each(AFFORDANCES)("catches $name escaping its guard", (affordance) => {
        const lifted = source().replace(affordance.guard, "{(");

        expect(
            unguardedAffordances(lifted),
            "removing the guard is the refactor this suite exists to catch; a source-string pin would still pass it",
        ).toEqual([affordance.name]);
    });

    it("does not confuse an absent control for a guarded one", () => {
        const removed = source().replace(AFFORDANCES[0].marker, 'aria-label={t("somethingElse")}');

        expect(
            unguardedAffordances(removed),
            "a missing marker is a stale test rather than a leak, and the presence check above is what reports it",
        ).toEqual([]);
    });
});
