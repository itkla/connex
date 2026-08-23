import { describe, expect, it } from "vitest";

import {
    ASK_CONNEX_DEFAULT_WIDTH,
    ASK_CONNEX_TERMINAL_REASONS,
    ASK_CONNEX_WIDTH_REM,
    askConnexActiveState,
    askConnexRecovery,
    askConnexSessionActivity,
    askConnexTerminalKind,
    askConnexWidthLength,
    askConnexWidthStorageKey,
    boundedAnswerEntries,
    filterAskConnexSessions,
    groupAskConnexSessions,
    isAskConnexAuthorizationWithdrawal,
    parseStoredAskConnexWidth,
} from "@/app/lib/askConnexSurface";
import type { AiChatSession } from "@/app/lib/types";

const NOW = Date.parse("2026-03-10T12:00:00Z");

/**
 * Runs one assertion with the process clock in a zone east of UTC.
 *
 * Activity times arrive in MySQL's offset-less `YYYY-MM-DD HH:MM:SS.ffffff` form, which names a UTC
 * instant. Read as local time it lands hours away from the instant it names — and a suite pinned to
 * UTC, as CI is, cannot tell the two readings apart.
 */
function inZone<T>(zone: string, run: () => T): T {
    const previous = process.env.TZ;
    process.env.TZ = zone;
    try {
        return run();
    } finally {
        if (previous === undefined) delete process.env.TZ;
        else process.env.TZ = previous;
    }
}

function session(overrides: Partial<AiChatSession> = {}): AiChatSession {
    return {
        id: 1,
        workspaceId: 7,
        createdByUserId: 3,
        title: "Sumitomo review",
        visibility: "private",
        status: "active",
        archived: false,
        ownedByCurrentUser: true,
        historySummarized: false,
        participationStatus: null,
        lastMessageAt: "2026-03-10 11:00:00.000000",
        archivedAt: null,
        createdAt: "2026-03-01 09:00:00.000000",
        updatedAt: "2026-03-10 11:00:00.000000",
        ...overrides,
    };
}

describe("drawer width", () => {
    it("renders each state from the one shared table the shell also reads", () => {
        expect(askConnexWidthLength("compact")).toBe(`${ASK_CONNEX_WIDTH_REM.compact}rem`);
        expect(askConnexWidthLength("comfortable")).toBe(`${ASK_CONNEX_WIDTH_REM.comfortable}rem`);
    });

    it("gives comfortable more room than compact", () => {
        expect(ASK_CONNEX_WIDTH_REM.comfortable).toBeGreaterThan(ASK_CONNEX_WIDTH_REM.compact);
    });

    it("scopes the stored preference to the member and the workspace", () => {
        expect(askConnexWidthStorageKey(7, 11)).toBe("connex:view:7:11:ask-connex:width");
        expect(askConnexWidthStorageKey(null, null)).toBe("connex:view:anon:none:ask-connex:width");
    });

    it("refuses anything that is not a width it can render", () => {
        expect(parseStoredAskConnexWidth("compact")).toBe("compact");
        expect(parseStoredAskConnexWidth("comfortable")).toBe("comfortable");
        expect(parseStoredAskConnexWidth("wide")).toBeNull();
        expect(parseStoredAskConnexWidth("")).toBeNull();
        expect(parseStoredAskConnexWidth(null)).toBeNull();
        expect(ASK_CONNEX_DEFAULT_WIDTH).toBe("compact");
    });
});

describe("bounded answer lists", () => {
    it("withholds nothing when the surface has no cap", () => {
        const entries = ["a", "b", "c", "d", "e", "f", "g"];
        expect(boundedAnswerEntries(entries, null)).toEqual({ entries, hidden: 0 });
    });

    it("keeps a list that only exceeds the cap by one intact", () => {
        const entries = ["a", "b", "c", "d", "e", "f"];
        expect(boundedAnswerEntries(entries, 5)).toEqual({ entries, hidden: 0 });
    });

    it("stops at the cap and reports exactly what it withheld", () => {
        const entries = ["a", "b", "c", "d", "e", "f", "g"];
        expect(boundedAnswerEntries(entries, 5)).toEqual({
            entries: ["a", "b", "c", "d", "e"],
            hidden: 2,
        });
    });

    it("copies rather than aliasing the caller's list", () => {
        const entries = ["a"];
        const result = boundedAnswerEntries(entries, null);
        expect(result.entries).not.toBe(entries);
        expect(result.entries).toEqual(entries);
    });
});

describe("session rail organization", () => {
    it("bands by last activity, newest first inside each band", () => {
        const groups = groupAskConnexSessions(
            [
                session({ id: 1, lastMessageAt: "2026-03-10 09:00:00.000000" }),
                session({ id: 2, lastMessageAt: "2026-03-08 09:00:00.000000" }),
                session({ id: 3, lastMessageAt: "2026-01-02 09:00:00.000000" }),
                session({ id: 4, lastMessageAt: "2026-03-10 11:30:00.000000" }),
            ],
            [],
            NOW,
        );
        expect(groups.map((group) => group.key)).toEqual(["last24h", "last7d", "earlier"]);
        expect(groups[0]?.sessions.map((item) => item.id)).toEqual([4, 1]);
        expect(groups[1]?.sessions.map((item) => item.id)).toEqual([2]);
        expect(groups[2]?.sessions.map((item) => item.id)).toEqual([3]);
    });

    it("reads the offset-less wire time as the UTC instant it names, not as local time", () => {
        inZone("Asia/Tokyo", () => {
            expect(askConnexSessionActivity(session({
                lastMessageAt: "2026-03-09 16:00:00.123456",
            }))).toBe(Date.parse("2026-03-09T16:00:00.123Z"));
        });
    });

    it("bands a chat active twenty hours ago the way its own relative time reads it", () => {
        inZone("Asia/Tokyo", () => {
            const groups = groupAskConnexSessions(
                [session({ id: 1, lastMessageAt: "2026-03-09 16:00:00.123456" })],
                [],
                NOW,
            );
            expect(groups.map((group) => group.key)).toEqual(["last24h"]);
        });
    });

    it("puts invitations first because they are a decision, not a chat", () => {
        const groups = groupAskConnexSessions(
            [session({ id: 1 })],
            [session({ id: 9, participationStatus: "invited" })],
            NOW,
        );
        expect(groups[0]?.key).toBe("invitations");
        expect(groups[0]?.sessions.map((item) => item.id)).toEqual([9]);
    });

    it("omits a band with nothing in it rather than rendering an empty heading", () => {
        const groups = groupAskConnexSessions([session({ id: 1 })], [], NOW);
        expect(groups.map((group) => group.key)).toEqual(["last24h"]);
    });

    it("returns no bands at all when there is nothing to show", () => {
        expect(groupAskConnexSessions([], [], NOW)).toEqual([]);
    });

    it("falls back to when a chat last changed if it carries no message time", () => {
        expect(askConnexSessionActivity(session({
            lastMessageAt: null,
            updatedAt: "2026-03-09 08:00:00.000000",
        }))).toBe(Date.parse("2026-03-09T08:00:00Z"));
    });

    it("treats an unreadable activity time as no activity instead of throwing", () => {
        expect(askConnexSessionActivity(session({
            lastMessageAt: "not a date",
            updatedAt: "also not a date",
        }))).toBe(0);
    });

    it("does not mutate the caller's list while ordering it", () => {
        const sessions = [
            session({ id: 1, lastMessageAt: "2026-03-08 09:00:00.000000" }),
            session({ id: 2, lastMessageAt: "2026-03-10 09:00:00.000000" }),
        ];
        groupAskConnexSessions(sessions, [], NOW);
        expect(sessions.map((item) => item.id)).toEqual([1, 2]);
    });

    it("matches titles case-insensitively and passes everything through an empty query", () => {
        const sessions = [session({ id: 1, title: "Sumitomo review" }), session({ id: 2, title: "Pipeline" })];
        expect(filterAskConnexSessions(sessions, "sumi").map((item) => item.id)).toEqual([1]);
        expect(filterAskConnexSessions(sessions, "   ").map((item) => item.id)).toEqual([1, 2]);
        expect(filterAskConnexSessions(sessions, "nothing")).toEqual([]);
    });
});

describe("the active chat's state", () => {
    it("leads with the answer being written", () => {
        expect(askConnexActiveState({ phase: "running", pendingApprovals: 2 })).toBe("running");
        expect(askConnexActiveState({ phase: "accepted", pendingApprovals: 0 })).toBe("running");
    });

    it("reports a waiting proposal ahead of a settled failure", () => {
        expect(askConnexActiveState({ phase: "failed", pendingApprovals: 1 }))
            .toBe("awaitingApproval");
    });

    it("reports a settled failure when nothing is waiting on the member", () => {
        expect(askConnexActiveState({ phase: "failed", pendingApprovals: 0 })).toBe("failed");
        expect(askConnexActiveState({ phase: "timed_out", pendingApprovals: 0 })).toBe("failed");
    });

    it("says nothing about a chat that is simply sitting there", () => {
        expect(askConnexActiveState({ phase: "idle", pendingApprovals: 0 })).toBeNull();
        expect(askConnexActiveState({ phase: "resolved", pendingApprovals: 0 })).toBeNull();
        expect(askConnexActiveState({ phase: "cancelled", pendingApprovals: 0 })).toBeNull();
    });
});

describe("failed-answer recovery", () => {
    it("offers nothing while the answer has not settled badly", () => {
        for (const phase of ["idle", "accepted", "running", "resolved", "cancelled"]) {
            expect(askConnexRecovery(phase, null, true, true)).toEqual({
                retry: false,
                continueFromPartial: false,
                narrowScope: false,
                narrowScopeFirst: false,
            });
        }
    });

    it("offers the same question again, from where it stopped, on an ordinary failure", () => {
        expect(askConnexRecovery("failed", "request_failed", true, true)).toEqual({
            retry: true,
            continueFromPartial: true,
            narrowScope: false,
            narrowScopeFirst: false,
        });
    });

    it("withholds continuing when nothing was retained to continue from", () => {
        expect(askConnexRecovery("failed", "request_failed", true, false).continueFromPartial)
            .toBe(false);
    });

    it("never advises narrowing for a reason nobody classified", () => {
        const recovery = askConnexRecovery("failed", "a_reason_from_a_later_release", true, true);
        expect(recovery.narrowScope).toBe(false);
        expect(recovery.narrowScopeFirst).toBe(false);
        expect(recovery.retry).toBe(true);
    });

    it("leads with narrowing and offers nothing that re-asks the same question", () => {
        for (const reason of [
            "step_cap_exceeded",
            "agent_backstop_exceeded",
            "tool_result_budget_exhausted",
        ]) {
            expect(askConnexRecovery("failed", reason, true, true)).toEqual({
                retry: false,
                continueFromPartial: false,
                narrowScope: true,
                narrowScopeFirst: true,
            });
        }
    });

    it("offers only the same question later when an allowance or capacity ran out", () => {
        for (const reason of [
            "budget_exhausted",
            "quota_exhausted",
            "org_invocation_quota_exhausted",
            "invocation_capacity_exhausted",
            "generation_capacity",
        ]) {
            expect(askConnexRecovery("failed", reason, true, true)).toEqual({
                retry: false,
                continueFromPartial: false,
                narrowScope: false,
                narrowScopeFirst: false,
            });
        }
    });

    it("offers nothing once the member's authority to read the answer was withdrawn", () => {
        for (const reason of ["access_revoked", "restrictions_changed"]) {
            expect(isAskConnexAuthorizationWithdrawal(reason)).toBe(true);
            expect(askConnexRecovery("failed", reason, true, true)).toEqual({
                retry: false,
                continueFromPartial: false,
                narrowScope: false,
                narrowScopeFirst: false,
            });
        }
        expect(isAskConnexAuthorizationWithdrawal("request_failed")).toBe(false);
        expect(isAskConnexAuthorizationWithdrawal(null)).toBe(false);
    });

    it("offers nothing when the feature is switched off or the input cannot be read", () => {
        for (const reason of ["workspace_disabled", "image_input_unsupported"]) {
            expect(askConnexRecovery("failed", reason, true, true)).toEqual({
                retry: false,
                continueFromPartial: false,
                narrowScope: false,
                narrowScopeFirst: false,
            });
        }
    });

    it("does not tell a synthesis failure to cover less, because retrieval succeeded", () => {
        expect(askConnexRecovery("failed", "skill_budget_exceeded", true, true)).toEqual({
            retry: true,
            continueFromPartial: true,
            narrowScope: false,
            narrowScopeFirst: false,
        });
    });

    it("offers no route at all when the question can no longer be re-asked", () => {
        expect(askConnexRecovery("failed", "step_cap_exceeded", false, true)).toEqual({
            retry: false,
            continueFromPartial: false,
            narrowScope: false,
            narrowScopeFirst: true,
        });
    });

    it("offers the same question again on a timeout, which says nothing about breadth", () => {
        for (const reason of [
            null,
            "generation_timeout",
            "turn_deadline_exceeded",
            "provider_idle_timeout",
        ]) {
            const recovery = askConnexRecovery("timed_out", reason, true, false);
            expect(recovery.retry).toBe(true);
            expect(recovery.narrowScope).toBe(false);
            expect(recovery.narrowScopeFirst).toBe(false);
        }
    });
});

describe("the terminal reason vocabulary", () => {
    /**
     * The reasons the server can settle a turn with, from `AiChatTurnTerminalCoordinator` and
     * `AiAssistantTerminalReasons`, plus the two this client settles a turn with itself. A reason
     * added to the classifier without being added here fails this test rather than quietly
     * inheriting whatever the default happens to be.
     */
    const KNOWN = [
        "access_revoked",
        "agent_backstop_exceeded",
        "attachment_auto_write_blocked",
        "budget_exhausted",
        "generation_capacity",
        "generation_timeout",
        "image_input_unsupported",
        "internal_error",
        "invocation_capacity_exhausted",
        "malformed_output",
        "no_progress",
        "org_invocation_quota_exhausted",
        "provider_error",
        "provider_idle_timeout",
        "quota_exhausted",
        "reconciliation_failed",
        "request_failed",
        "restrictions_changed",
        "schema_repair_failed",
        "skill_budget_exceeded",
        "step_cap_exceeded",
        "tool_outside_skill_authority",
        "tool_result_budget_exhausted",
        "turn_deadline_exceeded",
        "workspace_disabled",
    ];

    it("classifies every reason the server and this client can settle a turn with", () => {
        expect(ASK_CONNEX_TERMINAL_REASONS).toEqual(KNOWN);
    });

    it("states each reason with the explanation its class earns", () => {
        expect(KNOWN.map((reason) => [reason, askConnexTerminalKind(reason)])).toEqual([
            ["access_revoked", { category: "authorization", message: "accessRevoked" }],
            ["agent_backstop_exceeded", { category: "breadth", message: "breadthSteps" }],
            ["attachment_auto_write_blocked", { category: "generic", message: "generic" }],
            ["budget_exhausted", { category: "capacity", message: "budget" }],
            ["generation_capacity", { category: "capacity", message: "capacity" }],
            ["generation_timeout", { category: "generic", message: "generic" }],
            ["image_input_unsupported", { category: "unsupportedInput", message: "imageUnsupported" }],
            ["internal_error", { category: "generic", message: "generic" }],
            ["invocation_capacity_exhausted", { category: "capacity", message: "capacity" }],
            ["malformed_output", { category: "generic", message: "generic" }],
            ["no_progress", { category: "generic", message: "generic" }],
            ["org_invocation_quota_exhausted", { category: "capacity", message: "capacity" }],
            ["provider_error", { category: "generic", message: "generic" }],
            ["provider_idle_timeout", { category: "generic", message: "generic" }],
            ["quota_exhausted", { category: "capacity", message: "capacity" }],
            ["reconciliation_failed", { category: "generic", message: "generic" }],
            ["request_failed", { category: "generic", message: "generic" }],
            ["restrictions_changed", { category: "authorization", message: "restrictionsChanged" }],
            ["schema_repair_failed", { category: "generic", message: "generic" }],
            ["skill_budget_exceeded", { category: "synthesis", message: "skillBudget" }],
            ["step_cap_exceeded", { category: "breadth", message: "breadthSteps" }],
            ["tool_outside_skill_authority", { category: "generic", message: "toolAuthority" }],
            ["tool_result_budget_exhausted", { category: "breadth", message: "breadthResults" }],
            ["turn_deadline_exceeded", { category: "generic", message: "generic" }],
            ["workspace_disabled", { category: "availability", message: "workspaceDisabled" }],
        ]);
    });

    it("falls back to the generic failure rather than guessing at an unknown reason", () => {
        expect(askConnexTerminalKind("a_reason_from_a_later_release"))
            .toEqual({ category: "generic", message: "generic" });
        expect(askConnexTerminalKind(null)).toEqual({ category: "generic", message: "generic" });
    });
});
