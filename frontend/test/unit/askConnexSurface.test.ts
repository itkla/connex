import { readFileSync } from "node:fs";
import { join } from "node:path";

import { describe, expect, it } from "vitest";

import {
    ASK_CONNEX_DEFAULT_WIDTH,
    ASK_CONNEX_TERMINAL_REASONS,
    ASK_CONNEX_WIDTH_REM,
    askConnexActiveState,
    askConnexBoundRetryPrompt,
    askConnexRecovery,
    askConnexSessionActivity,
    askConnexSessionReaders,
    askConnexTerminalKind,
    askConnexTimedOutMessage,
    askConnexTurnWriteState,
    askConnexWidthLength,
    askConnexWidthStorageKey,
    filterAskConnexSessions,
    groupAskConnexSessions,
    isAskConnexAuthorizationWithdrawal,
    parseStoredAskConnexWidth,
} from "@/app/lib/askConnexSurface";
import { askConnexToolCall } from "@/test/unit/helpers/askConnexToolCards";
import type { AiChatMessage, AiChatParticipant, AiChatProgressItem, AiChatSession, AiChatTurnToolCall } from "@/app/lib/types";

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

describe("who a shared chat is being read by", () => {
    function participant(overrides: Partial<AiChatParticipant> = {}): AiChatParticipant {
        return {
            userId: 1,
            displayName: "Aiko Tanaka",
            profilePictureUrl: null,
            role: "participant",
            status: "joined",
            currentUser: false,
            ...overrides,
        };
    }

    it("counts the members who joined and not the ones still invited", () => {
        const readers = askConnexSessionReaders([
            participant({ userId: 1, role: "owner" }),
            participant({ userId: 2, status: "invited", displayName: "Kenji Mori" }),
            participant({ userId: 3, displayName: "Sara Ito" }),
        ]);

        expect(readers.map((reader) => reader.userId)).toEqual([1, 3]);
    });

    it("returns nobody when every share is still an unaccepted invitation", () => {
        expect(askConnexSessionReaders([
            participant({ userId: 2, status: "invited" }),
            participant({ userId: 3, status: "invited" }),
        ])).toEqual([]);
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

const REPLAY_RISK_REASONS = [
    "generation_timeout", "turn_deadline_exceeded", "provider_idle_timeout", "no_progress",
] as const;

const READ_ONLY_PROGRESS: AiChatProgressItem[] = [
    { seq: 0, source: "scope", status: "complete", count: null, truncated: false },
    { seq: 1, source: "records", status: "complete", count: 2, truncated: false },
    { seq: 65, source: "answer", status: "timed_out", count: null, truncated: false },
];

const WRITE_FREE_TURN = { turnId: 9, progress: READ_ONLY_PROGRESS };

describe("the exact failed question Retry can repeat", () => {
    const target = {
        sessionId: 4, turnId: 9, prompt: "Read Sato's tasks", authorUserId: 3, requestMessageSeq: 6,
    };
    const question: AiChatMessage = {
        id: 22, sessionId: 4, seq: 6, authorKind: "user", authorUserId: 3,
        authorDisplayName: "Aiko", content: target.prompt, createdAt: "2026-10-18 11:59:00",
    };

    it("uses the captured turn's own question", () => {
        expect(askConnexBoundRetryPrompt(target, target, [question], [])).toBe(target.prompt);
    });

    it("refuses a different turn, a different session, or a question it never captured", () => {
        expect(askConnexBoundRetryPrompt(target, { ...target, turnId: 10 }, [question], [])).toBeNull();
        expect(askConnexBoundRetryPrompt(target, { ...target, sessionId: 5 }, [question], [])).toBeNull();
        expect(askConnexBoundRetryPrompt(null, target, [question], [])).toBeNull();
    });

    it("refuses missing, withheld, optimistic, and ambiguous questions", () => {
        for (const messages of [
            [], [{ ...question, contentWithheld: true }], [{ ...question, id: -1 }],
            [question, { ...question, id: 23, seq: 7 }], [{ ...question, authorUserId: 7 }],
            [{ ...question, seq: 5 }],
        ]) {
            expect(askConnexBoundRetryPrompt(target, target, messages, [])).toBeNull();
        }
    });

    it("refuses a newer transcript request even when it has no visible writes", () => {
        expect(askConnexBoundRetryPrompt(target, target, [question, {
            ...question, id: 23, seq: 7, content: "Create a follow-up task",
        }], [])).toBeNull();
        expect(askConnexBoundRetryPrompt(target, target, [{ ...question, id: 60, seq: 60 }], []))
            .toBeNull();
    });

    it("refuses a newer turn's card even before its question arrives", () => {
        expect(askConnexBoundRetryPrompt(target, target, [question], [
            askConnexToolCall({ turnId: 10, status: "executed" }),
        ])).toBeNull();
    });
});

describe("current-turn write evidence", () => {
    const projectedTurn = (toolCalls: AiChatTurnToolCall[]) => ({
        ...WRITE_FREE_TURN,
        progress: [...READ_ONLY_PROGRESS, {
            seq: 2, source: "tasks", status: "complete", count: 1, truncated: false,
        } satisfies AiChatProgressItem],
        toolCalls,
    });

    it("keeps a known commitment even when evidence is missing or reconciliation fails", () => {
        expect(askConnexTurnWriteState({
            turnId: 9, progress: [], toolCalls: null, writeCommitted: true, evidenceReconciled: false,
        }, [], null)).toBe("committed");
    });

    it("does not trust a cached proposal while its execution evidence is stale", () => {
        expect(askConnexTurnWriteState({
            ...projectedTurn([{ toolName: "assign_owner", tier: "CONFIRM", status: "proposed" }]),
            evidenceReconciled: false,
        }, [], 9)).toBe("uncertain");
    });

    it.each(["list_tasks", "list_activities"])("restores Retry for a read-only %s timeout", (toolName) => {
        const state = askConnexTurnWriteState(projectedTurn([
            { toolName, tier: "READ", status: "executed" },
            { toolName, tier: "READ", status: "failed" },
        ]), [], 9);
        expect(state).toBe("none");
        expect(askConnexRecovery("timed_out", "turn_deadline_exceeded", true, true, state).retry).toBe(true);
    });

    it.each(["AUTO", "CONFIRM"])("blocks Retry for an executed %s write omitted from cards", (tier) => {
        const turn = projectedTurn([{ toolName: "create_task", tier, status: "executed" }]);
        const state = askConnexTurnWriteState(turn, [], 9);
        expect(state).toBe("committed");
        expect(askConnexTurnWriteState(turn, [], null)).toBe("committed");
        expect(askConnexRecovery("timed_out", "turn_deadline_exceeded", true, true, state).retry).toBe(false);
    });

    it.each(["AUTO", "CONFIRM"])("permits Retry for an unexecuted %s proposal", (tier) => {
        for (const status of ["proposed", "rejected", "failed"]) {
            const state = askConnexTurnWriteState(projectedTurn([
                { toolName: "assign_owner", tier, status },
            ]), [], 9);
            expect(state).toBe("none");
            expect(askConnexRecovery("failed", "provider_error", true, true, state).retry).toBe(true);
        }
    });

    it.each(["rejected", "failed"] as const)("does not infer execution from a %s proposal's decision timestamp", (status) => {
        const state = askConnexTurnWriteState(projectedTurn([
            { toolName: "assign_owner", tier: "CONFIRM", status },
        ]), [askConnexToolCall({
            toolName: "assign_owner", tier: "confirm", status, executedAt: "2026-10-18T12:00:00Z",
        })], 9);
        expect(state).toBe("none");
        expect(askConnexRecovery("failed", "provider_error", true, true, state).retry).toBe(true);
    });

    it.each([
        { toolName: "future_tool", tier: null, status: "executed" },
        { toolName: "create_task", tier: "FUTURE", status: "executed" },
        { toolName: "", tier: "READ", status: "executed" },
        { toolName: "create_task", tier: "AUTO", status: "future_state" },
    ])("withholds Retry for unknown execution evidence: %j", (call) => {
        const state = askConnexTurnWriteState(projectedTurn([call]), [], 9);
        expect(state).toBe("uncertain");
        expect(askConnexRecovery("failed", "provider_error", true, true, state).retry).toBe(false);
    });

    it("requires reconciliation and settled progress even with an empty projection", () => {
        expect(askConnexTurnWriteState(projectedTurn([]), [], null)).toBe("uncertain");
        expect(askConnexTurnWriteState({ ...projectedTurn([]), progress: [] }, [], 9)).toBe("uncertain");
        expect(askConnexTurnWriteState(projectedTurn([]), [], 9)).toBe("none");
    });

    it("keeps committed cards authoritative over a stale write-free projection", () => {
        expect(askConnexTurnWriteState(projectedTurn([]), [askConnexToolCall({ status: "executed" })], 9))
            .toBe("committed");
        expect(askConnexTurnWriteState(projectedTurn([
            { toolName: "create_task", tier: "AUTO", status: "undone" },
        ]), [], 9)).toBe("committed");
    });

    it("retains conservative legacy behavior when the projection is absent or null", () => {
        const turn = projectedTurn([]);
        expect(askConnexTurnWriteState({ ...turn, toolCalls: undefined }, [], 9)).toBe("uncertain");
        expect(askConnexTurnWriteState({ ...turn, toolCalls: null }, [], 9)).toBe("uncertain");
    });

    it("treats a missing turn or an unreconciled snapshot as uncertain", () => {
        expect(askConnexTurnWriteState({ ...WRITE_FREE_TURN, turnId: null }, [], null)).toBe("uncertain");
        expect(askConnexTurnWriteState(WRITE_FREE_TURN, [], null)).toBe("uncertain");
        expect(askConnexTurnWriteState(WRITE_FREE_TURN, [], 8)).toBe("uncertain");
    });

    it("allows a reconciled write-free turn even if a previous turn wrote something", () => {
        expect(askConnexTurnWriteState(WRITE_FREE_TURN, [], 9)).toBe("none");
        expect(askConnexTurnWriteState(WRITE_FREE_TURN, [askConnexToolCall({
            turnId: 8, status: "executed",
        })], 9)).toBe("none");
    });

    it.each(["auto", "confirm"] as const)("recognizes committed %s writes before reconciliation", (tier) => {
        expect(askConnexTurnWriteState(WRITE_FREE_TURN, [askConnexToolCall({
            status: "executed", tier,
        })], null)).toBe("committed");
    });

    it("keeps undone writes and execution timestamps as evidence of a committed write", () => {
        expect(askConnexTurnWriteState(WRITE_FREE_TURN, [askConnexToolCall({ status: "undone" })], 9))
            .toBe("committed");
        expect(askConnexTurnWriteState(WRITE_FREE_TURN, [askConnexToolCall({
            status: "failed", executedAt: "2026-10-18T12:00:00Z",
        })], 9)).toBe("committed");
    });

    it("treats an unresolved automatic write as uncertain", () => {
        expect(askConnexTurnWriteState(WRITE_FREE_TURN, [askConnexToolCall({ tier: "auto" })], 9))
            .toBe("uncertain");
    });

    it.each(["activities", "tasks", "notes", "actions", "other"] as const)("does not infer no writes from omitted cards for %s", (source) => {
        for (const status of ["complete", "failed", "proposed", "running", "skipped"] as const) {
            const progress: AiChatProgressItem[] = [
                ...READ_ONLY_PROGRESS,
                { seq: 2, source, status, count: 1, truncated: false },
            ];
            expect(askConnexTurnWriteState({ turnId: 9, progress }, [], 9)).toBe("uncertain");
            expect(askConnexTurnWriteState({
                turnId: 9, progress: progress.map((item) => ({ ...item, count: null })),
            }, [], 9)).toBe("uncertain");
        }
    });

    it.each(["records", "deals", "files", "metrics", "schedule"] as const)("preserves retry for write-free %s reads", (source) => {
        const progress: AiChatProgressItem[] = [
            ...READ_ONLY_PROGRESS,
            { seq: 2, source, status: "complete", count: 100, truncated: true },
        ];
        const state = askConnexTurnWriteState({ turnId: 9, progress }, [], 9);
        expect(state).toBe("none");
        for (const reason of REPLAY_RISK_REASONS) {
            expect(askConnexRecovery("failed", reason, true, true, state).retry).toBe(true);
        }
    });

    it("does not treat missing or still-running progress as a write-free settled snapshot", () => {
        expect(askConnexTurnWriteState({ turnId: 9, progress: [] }, [], 9)).toBe("uncertain");
        expect(askConnexTurnWriteState({
            turnId: 9, progress: READ_ONLY_PROGRESS.map((item) => ({ ...item, status: "running" })),
        }, [], 9)).toBe("uncertain");
    });

    it.each([
        { status: "proposed", progressStatus: "proposed" },
        { status: "rejected", progressStatus: "skipped" },
        { status: "failed", progressStatus: "failed" },
    ] as const)("does not mistake a $status proposal for a committed write", ({ status, progressStatus }) => {
        const progress: AiChatProgressItem[] = [
            ...READ_ONLY_PROGRESS,
            { seq: 2, source: "actions", status: progressStatus, count: null, truncated: false },
        ];
        expect(askConnexTurnWriteState({ turnId: 9, progress }, [askConnexToolCall({ status })], 9))
            .toBe("uncertain");
    });
});

describe("failed-answer recovery", () => {
    describe.each(REPLAY_RISK_REASONS)("%s", (reason) => {
        it.each(["none", "committed", "uncertain"] as const)("offers safe recovery with %s writes", (writeState) => {
            for (const phase of ["failed", "timed_out"]) {
                expect(askConnexRecovery(phase, reason, true, true, writeState)).toEqual({
                    retry: writeState === "none",
                    continueFromPartial: true,
                    narrowScope: false,
                    narrowScopeFirst: false,
                });
                expect(askConnexRecovery(phase, reason, true, false, writeState).continueFromPartial)
                    .toBe(false);
                expect(askConnexRecovery(phase, reason, false, true, writeState).retry).toBe(false);
                expect(askConnexRecovery(phase, reason, false, true, writeState).continueFromPartial)
                    .toBe(false);
            }
        });
    });

    it("offers nothing while the answer has not settled badly", () => {
        for (const phase of ["idle", "accepted", "running", "resolved", "cancelled"]) {
            expect(askConnexRecovery(phase, null, true, true, "none")).toEqual({
                retry: false,
                continueFromPartial: false,
                narrowScope: false,
                narrowScopeFirst: false,
            });
        }
    });

    it("offers the same question again, from where it stopped, on an ordinary failure", () => {
        expect(askConnexRecovery("failed", "request_failed", true, true, "none")).toEqual({
            retry: true,
            continueFromPartial: true,
            narrowScope: false,
            narrowScopeFirst: false,
        });
    });

    it("withholds continuing when nothing was retained to continue from", () => {
        expect(askConnexRecovery("failed", "request_failed", true, false, "none").continueFromPartial)
            .toBe(false);
    });

    it("never advises narrowing for a reason nobody classified", () => {
        const recovery = askConnexRecovery("failed", "a_reason_from_a_later_release", true, true, "none");
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
            expect(askConnexRecovery("failed", reason, true, true, "none")).toEqual({
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
            expect(askConnexRecovery("failed", reason, true, true, "none")).toEqual({
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
            expect(askConnexRecovery("failed", reason, true, true, "none")).toEqual({
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
        for (const reason of [
            "workspace_disabled",
            "context_window_too_small",
            "image_input_unsupported",
        ]) {
            expect(askConnexRecovery("failed", reason, true, true, "none")).toEqual({
                retry: false,
                continueFromPartial: false,
                narrowScope: false,
                narrowScopeFirst: false,
            });
        }
    });

    /**
     * An answer that stopped part-way may already have saved a task, note, activity or tag, and
     * nothing on this client can say whether it did. Retry sends the original question straight
     * back as a new request, so offering it here would be offering to make a second copy. Picking
     * up from the retained words only fills the composer, so it survives.
     */
    it("withholds the one-press repeat when the answer stopped part-way through", () => {
        expect(askConnexRecovery("failed", "owner_lost", true, true, "none")).toEqual({
            retry: false,
            continueFromPartial: true,
            narrowScope: false,
            narrowScopeFirst: false,
        });
        expect(isAskConnexAuthorizationWithdrawal("owner_lost")).toBe(false);
    });

    it("does not tell a synthesis failure to cover less, because retrieval succeeded", () => {
        expect(askConnexRecovery("failed", "skill_budget_exceeded", true, true, "none")).toEqual({
            retry: true,
            continueFromPartial: true,
            narrowScope: false,
            narrowScopeFirst: false,
        });
    });

    it("offers no route at all when the question can no longer be re-asked", () => {
        expect(askConnexRecovery("failed", "step_cap_exceeded", false, true, "none")).toEqual({
            retry: false,
            continueFromPartial: false,
            narrowScope: false,
            narrowScopeFirst: true,
        });
    });

    it("states a timeout as a timeout, including one this build cannot classify", () => {
        for (const reason of [
            null,
            "generation_timeout",
            "turn_deadline_exceeded",
            "provider_idle_timeout",
            "a_timeout_from_a_later_release",
        ]) {
            expect(askConnexTimedOutMessage(reason)).toBe("timeout");
        }
    });

    it("states a timed-out turn that named a non-timeout reason with that reason", () => {
        expect(askConnexTimedOutMessage("budget_exhausted")).toBe("budget");
    });

    it("offers the same question again on a timeout, which says nothing about breadth", () => {
        for (const reason of [
            null,
            "generation_timeout",
            "turn_deadline_exceeded",
            "provider_idle_timeout",
        ]) {
            const recovery = askConnexRecovery("timed_out", reason, true, false, "none");
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
        "context_window_too_small",
        "generation_capacity",
        "generation_timeout",
        "image_input_unsupported",
        "internal_error",
        "invocation_capacity_exhausted",
        "malformed_output",
        "no_progress",
        "org_invocation_quota_exhausted",
        "owner_lost",
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
            ["context_window_too_small",
                { category: "availability", message: "contextWindowTooSmall" }],
            ["generation_capacity", { category: "capacity", message: "capacity" }],
            ["generation_timeout", { category: "transient", message: "timeout" }],
            ["image_input_unsupported", { category: "unsupportedInput", message: "imageUnsupported" }],
            ["internal_error", { category: "transient", message: "internal" }],
            ["invocation_capacity_exhausted", { category: "capacity", message: "capacity" }],
            ["malformed_output", { category: "transient", message: "unreadable" }],
            ["no_progress", { category: "transient", message: "stalled" }],
            ["org_invocation_quota_exhausted", { category: "capacity", message: "capacity" }],
            ["owner_lost", { category: "interrupted", message: "ownerLost" }],
            ["provider_error", { category: "transient", message: "provider" }],
            ["provider_idle_timeout", { category: "transient", message: "timeout" }],
            ["quota_exhausted", { category: "capacity", message: "capacity" }],
            ["reconciliation_failed", { category: "transient", message: "internal" }],
            ["request_failed", { category: "transient", message: "internal" }],
            ["restrictions_changed", { category: "authorization", message: "restrictionsChanged" }],
            ["schema_repair_failed", { category: "transient", message: "unreadable" }],
            ["skill_budget_exceeded", { category: "synthesis", message: "skillBudget" }],
            ["step_cap_exceeded", { category: "breadth", message: "breadthSteps" }],
            ["tool_outside_skill_authority", { category: "generic", message: "toolAuthority" }],
            ["tool_result_budget_exhausted", { category: "breadth", message: "breadthResults" }],
            ["turn_deadline_exceeded", { category: "transient", message: "timeout" }],
            ["workspace_disabled", { category: "availability", message: "workspaceDisabled" }],
        ]);
    });

    it("falls back to the generic failure rather than guessing at an unknown reason", () => {
        expect(askConnexTerminalKind("a_reason_from_a_later_release"))
            .toEqual({ category: "generic", message: "generic" });
        expect(askConnexTerminalKind(null)).toEqual({ category: "generic", message: "generic" });
    });
});

describe("what a stopped answer says, in both languages", () => {
    /**
     * A reader in either language reads one sentence and sees one set of buttons, so the two have
     * to say the same thing. This client withholds the one-press repeat for an answer that stopped
     * part-way because it may already have saved a task, note, activity or tag and nothing here
     * can tell. The sentence therefore may not reassure the member that asking again costs
     * nothing, and it has to say both that what was saved is kept and that it is worth looking at
     * before asking again.
     */
    const RETIRED_REASSURANCE: Record<string, readonly string[]> = {
        en: ["asking again is safe", "safe to ask again"],
        ja: ["問題ありません"],
    };
    const REQUIRED: Record<string, readonly string[]> = {
        en: ["already saved", "before you ask again"],
        ja: ["保存", "確認"],
    };

    function ownerLostCopy(locale: string): string {
        const catalog = JSON.parse(
            readFileSync(join(process.cwd(), "messages", locale, "common.json"), "utf8"),
        ) as { AskConnex: { turnOwnerLost: string } };
        return catalog.AskConnex.turnOwnerLost;
    }

    it.each(["en", "ja"])("never tells a %s reader that asking again is free of consequence", (locale) => {
        expect(askConnexRecovery("failed", "owner_lost", true, true, "none").retry).toBe(false);
        const copy = ownerLostCopy(locale);
        for (const reassurance of RETIRED_REASSURANCE[locale] ?? []) {
            expect(copy).not.toContain(reassurance);
        }
        for (const phrase of REQUIRED[locale] ?? []) {
            expect(copy).toContain(phrase);
        }
    });
});
