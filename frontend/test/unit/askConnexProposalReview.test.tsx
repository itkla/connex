import { type AnchorHTMLAttributes, type PropsWithChildren, type ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import AskConnexProposalReview, {
    AskConnexProposalReviewSummary,
} from "@/app/components/ask-connex/AskConnexProposalReview";
import AskConnexToolCard from "@/app/components/ask-connex/AskConnexToolCard";
import { NowProvider } from "@/app/hooks/useNow";
import {
    askConnexChangeApplicable,
    askConnexGroupedToolCallIds,
    askConnexProposalAppliable,
    askConnexProposalGroups,
    askConnexToolCardAffordances,
    askConnexUndoWindow,
    toggleAskConnexProposalExclusion,
    type AskConnexToolCardState,
} from "@/app/lib/askConnex";
import type { AiAssistantToolCallChangeState } from "@/app/lib/types";
import {
    askConnexCard as card,
    askConnexCardLabels as cardLabels,
    askConnexChange as change,
    askConnexReviewLabels as reviewLabels,
} from "@/test/unit/helpers/askConnexToolCards";

vi.mock("next/link", async () => {
    const React = await import("react");
    type LinkProps = PropsWithChildren<AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }>;
    return {
        default: ({ children, href, ...props }: LinkProps) =>
            React.createElement("a", { ...props, href }, children),
    };
});

const NOW = Date.parse("2026-08-22T12:00:00Z");


function render(node: ReactNode): string {
    return renderToStaticMarkup(<NowProvider value={NOW}>{node}</NowProvider>);
}

function renderCard(state: AskConnexToolCardState): string {
    return render(
        <AskConnexToolCard
            card={state}
            labels={cardLabels}
            actionsDisabled={false}
            onAction={() => {}}
            formatDeadline={(instant) => `deadline(${instant})`}
            formatRemaining={(instant) => `remaining(${instant})`}
        />,
    );
}

describe("assistant proposal review", () => {
    it("states the exact current and proposed values before anything is applied", () => {
        const markup = renderCard(card({ change: change() }));

        expect(markup).toContain("Proposed change");
        expect(markup).toContain("Owner");
        expect(markup).toContain("Now");
        expect(markup).toContain("Ada Owner");
        expect(markup).toContain("After");
        expect(markup).toContain("Grace Hopper");
        expect(markup).toContain("Apply the proposed change to Acme renewal");
        expect(markup).toContain("Discard the proposed change to Acme renewal");
        expect(markup).toContain("Open Acme renewal to make this change there");
    });

    it("writes a first assignment and a removal as real values rather than missing rows", () => {
        const assigning = renderCard(card({ change: change({ currentValue: null }) }));
        const clearing = renderCard(card({
            id: 32,
            requestSummary: "Remove the current owner",
            change: change({ proposedValue: null }),
        }));

        expect(assigning).toContain("—");
        expect(assigning).toContain("Not set");
        expect(assigning).toContain("Grace Hopper");
        expect(clearing).toContain("Ada Owner");
        expect(clearing).toContain("—");
        expect(clearing).toContain("Not set");
    });

    it("reads without colour: every review state carries its own sentence", () => {
        const states: Exclude<AiAssistantToolCallChangeState, "ready">[] = [
            "unchanged",
            "recordChanged",
            "permissionLost",
            "unresolved",
        ];
        for (const state of states) {
            expect(renderCard(card({ change: change({ state }) })))
                .toContain(cardLabels.changeState[state]);
        }
        expect(renderCard(card({ change: change() })))
            .not.toContain(cardLabels.changeState.unchanged);
    });

    it("offers apply only for a change the server still holds applicable", () => {
        expect(askConnexChangeApplicable(change())).toBe(true);
        expect(askConnexChangeApplicable(change({ state: "recordChanged" }))).toBe(false);
        expect(askConnexChangeApplicable(change({ state: "unchanged" }))).toBe(false);
        expect(askConnexChangeApplicable(change({ state: "permissionLost" }))).toBe(false);
        expect(askConnexChangeApplicable(change({ state: "unresolved" }))).toBe(false);
        expect(askConnexChangeApplicable(null)).toBe(false);

        const blocked = card({ change: change({ state: "permissionLost" }) });
        expect(askConnexToolCardAffordances(blocked, NOW)).toEqual(["reject"]);
        expect(renderCard(blocked)).not.toContain("Apply the proposed change to Acme renewal");
        expect(askConnexToolCardAffordances(card({ change: change() }), NOW))
            .toEqual(["reject", "approve"]);
    });

    it("never arms apply over a record the server will always refuse as moved", () => {
        const moved = card({ change: change({ state: "recordChanged" }) });
        const markup = renderCard(moved);

        expect(askConnexProposalAppliable(moved)).toBe(false);
        expect(askConnexToolCardAffordances(moved, NOW)).toEqual(["reject"]);
        expect(markup).not.toContain("Apply the proposed change to Acme renewal");
        expect(markup).toContain("Discard the proposed change to Acme renewal");
        expect(markup).toContain(cardLabels.changeState.recordChanged);
    });

    it("keeps a proposal whose last attempt was refused out of the applicable set", () => {
        const refused = card({ change: change(), failure: "proposalChanged" });
        const retryable = card({ change: change(), failure: "actionFailed" });

        expect(askConnexProposalAppliable(refused)).toBe(false);
        expect(askConnexToolCardAffordances(refused, NOW)).toEqual(["reject"]);
        expect(askConnexProposalAppliable(retryable)).toBe(true);
        expect(askConnexToolCardAffordances(retryable, NOW)).toEqual(["reject", "approve"]);
    });

    it("never shows a before-value the server withheld, or a control over it", () => {
        const withheld = card({
            change: null,
            requestSummary: "Assign an owner",
            target: { kind: "deal", id: null, label: null },
        });
        const markup = renderCard(withheld);

        expect(markup).not.toContain("Proposed change");
        expect(markup).not.toContain("Ada Owner");
        expect(markup).toContain("a record you can&#x27;t open");
        expect(askConnexToolCardAffordances(withheld, NOW)).toEqual(["reject"]);
        expect(markup).not.toContain("Apply the proposed change to");
    });

    it("writes an owner nobody here can name as who they are, not as an empty field", () => {
        const markup = renderCard(card({
            requestSummary: "Remove the current owner",
            change: change({
                currentValue: null,
                currentValueUnresolved: true,
                proposedValue: null,
            }),
        }));

        expect(markup).toContain("Someone no longer in this workspace");
        expect(markup).not.toContain("This is already the current value.");
        expect(markup).toContain("Apply the proposed change to Acme renewal");
    });

    it("writes a proposed value that is gone as gone, not as a clearing proposal", () => {
        const markup = renderCard(card({
            change: change({ proposedValue: null, state: "unresolved" }),
        }));

        expect(markup).toContain("No longer exists");
        expect(markup).toContain("The proposed value no longer exists in this workspace.");
        expect(markup).not.toContain("Apply the proposed change to Acme renewal");
    });
});

describe("completed assistant actions", () => {
    const executed = card({
        id: 41,
        toolName: "create_task",
        tier: "auto",
        status: "executed",
        requestSummary: "Create a task",
        outcomeSummary: "Task created",
        outcomeValues: [
            { field: "description", value: "Follow up with Kenji Sato" },
            { field: "dueDate", value: "2026-08-22" },
        ],
        createdRecord: { kind: "task", id: 74 },
        undoExpiresAt: "2026-08-22T12:10:00Z",
        undoAvailable: true,
        executedAt: "2026-08-22T12:00:00Z",
    });

    it("states what was written and until when it can be undone", () => {
        const markup = renderCard(executed);

        expect(markup).toContain("Task created");
        expect(markup).toContain("Task");
        expect(markup).toContain("description:Follow up with Kenji Sato");
        expect(markup).toContain("Due");
        expect(markup).toContain("dueDate:2026-08-22");
        expect(markup).toContain("You can undo this until deadline(2026-08-22T12:10:00Z)");
        expect(markup).toContain("Undo the assistant action for Acme renewal");
    });

    it("states every written value through the reader's own formatter", () => {
        const markup = renderCard({
            ...executed,
            toolName: "create_activity",
            outcomeSummary: "Activity created",
            outcomeValues: [
                { field: "type", value: "meeting" },
                { field: "start", value: "2026-03-12 13:00:00" },
                { field: "conferenceUrl", value: "https://example.invalid/a" },
            ],
            createdRecord: { kind: "activity", id: 88 },
        });

        expect(markup).toContain("type:meeting");
        expect(markup).toContain("start:2026-03-12 13:00:00");
        expect(markup).toContain("Detail");
        expect(markup).not.toContain(">conferenceUrl<");
    });

    it("opens the record the action created, with the related record still reachable", () => {
        const markup = renderCard(executed);

        expect(markup).toContain('href="/activity/tasks/74"');
        expect(markup).toContain("Open the task this action created");
        expect(markup).toContain('href="/records/deals/7"');
    });

    it("reports the undo window from the server deadline and only while it is real", () => {
        expect(askConnexUndoWindow(executed, NOW)).toEqual({
            state: "open",
            expiresAt: "2026-08-22T12:10:00Z",
            remainingMs: 600_000,
        });
        expect(askConnexUndoWindow(executed, NOW + 600_001)).toEqual({
            state: "closed",
            expiresAt: "2026-08-22T12:10:00Z",
        });
        expect(askConnexUndoWindow({ ...executed, undoExpiresAt: "not a deadline" }, NOW))
            .toEqual({ state: "none" });
        expect(askConnexUndoWindow({ ...executed, status: "undone" }, NOW))
            .toEqual({ state: "none" });
        expect(askConnexUndoWindow({ ...executed, undoAvailable: false }, NOW))
            .toEqual({ state: "none" });
        expect(askConnexUndoWindow({ ...executed, undoBlocked: true }, NOW))
            .toEqual({ state: "none" });
    });

    it("never leaves a dead undo control behind an expired window", () => {
        const expired = { ...executed, undoExpiresAt: "2026-08-22T11:50:00Z" };

        expect(askConnexToolCardAffordances(expired, NOW)).toEqual([]);
        const markup = renderCard(expired);
        expect(markup).toContain("Undo expired");
        expect(markup).not.toContain("You can undo this until");
        expect(markup).not.toContain("Undo the assistant action for Acme renewal");
    });

    it("never promises an undo to a viewer who has no way to make one", () => {
        const watching = renderCard({ ...executed, undoAvailable: false });

        expect(watching).not.toContain("You can undo this until");
        expect(watching).not.toContain("Undo the assistant action for Acme renewal");
    });

    it("keeps a failed undo readable instead of silently dropping the action", () => {
        const markup = renderCard({
            ...executed,
            failure: "undoConflict",
            undoBlocked: true,
        });

        expect(markup).toContain("This record changed after the assistant created it.");
        expect(markup).toContain("Task created");
        expect(markup).not.toContain("You can undo this until");
        expect(markup).not.toContain("Undo the assistant action for Acme renewal");
    });
});

describe("grouped proposal review", () => {
    const first = card({ id: 51, change: change() });
    const second = card({
        id: 52,
        target: { kind: "deal", id: 8, label: "Globex expansion" },
        requestSummary: "Change deal stage to: Won",
        toolName: "change_deal_stage",
        change: change({ field: "stage", currentValue: "Negotiation", proposedValue: "Won" }),
    });
    const blocked = card({
        id: 53,
        target: { kind: "deal", id: 9, label: "Initech pilot" },
        change: change({ state: "permissionLost" }),
    });
    const actionable = new Set([51, 52, 53]);

    it("groups an answer's proposals and counts what can actually be applied", () => {
        const [group] = askConnexProposalGroups(
            [first, second, blocked], actionable, new Set(),
        );

        expect(group.cards.map((entry) => entry.id)).toEqual([51, 52, 53]);
        expect(group.applicable).toBe(2);
        expect(group.selected).toBe(2);
        expect([...group.included]).toEqual([51, 52, 53]);
        expect(askConnexGroupedToolCallIds([group])).toEqual(new Set([51, 52, 53]));
    });

    it("leaves a lone proposal, a pair, a decided one, and another member's on their own cards", () => {
        expect(askConnexProposalGroups([first], actionable, new Set())).toEqual([]);
        expect(askConnexProposalGroups([first, second], actionable, new Set())).toEqual([]);
        expect(askConnexProposalGroups(
            [first, second, { ...blocked, status: "executed" }], actionable, new Set(),
        )).toEqual([]);
        expect(askConnexProposalGroups(
            [first, second, blocked], new Set([51, 52]), new Set(),
        )).toEqual([]);
        expect(askConnexProposalGroups(
            [first, second, { ...blocked, turnId: 10 }], actionable, new Set(),
        )).toEqual([]);
    });

    it("counts the whole answer, including what the batch already decided", () => {
        const [group] = askConnexProposalGroups(
            [
                first,
                { ...second, failure: "proposalChanged" },
                blocked,
                { ...card({ id: 54 }), status: "executed" },
                { ...card({ id: 55 }), status: "executed" },
                { ...card({ id: 56 }), status: "rejected" },
            ],
            new Set([51, 52, 53, 54, 55, 56]),
            new Set(),
        );

        expect(group.cards.map((entry) => entry.id)).toEqual([51, 52, 53]);
        expect(group.applied).toBe(2);
        expect(group.discarded).toBe(1);
        expect(group.failed).toBe(1);
        expect(group.applicable).toBe(1);
        expect(group.selected).toBe(1);
    });

    it("says what became of a batch that half went through, on every failed row", () => {
        const [group] = askConnexProposalGroups(
            [
                first,
                { ...second, failure: "proposalChanged" },
                { ...blocked, failure: "proposalPermissionLost" },
                { ...card({ id: 54 }), status: "executed" },
                { ...card({ id: 55 }), status: "executed" },
            ],
            new Set([51, 52, 53, 54, 55]),
            new Set(),
        );
        const markup = render(
            <AskConnexProposalReview
                group={group}
                labels={reviewLabels}
                cardLabels={cardLabels}
                actionsDisabled={false}
                onToggleInclusion={() => {}}
                onAction={() => {}}
                onApplySelected={() => {}}
            />,
        );

        expect(markup).toContain("2 applied");
        expect(markup).toContain("2 could not be applied");
        expect(markup).toContain("The target changed after this proposal was shown.");
        expect(markup).toContain("You no longer have permission to approve this proposal.");
        expect(markup).toContain('role="alert"');
        expect(group.selected).toBe(1);
    });

    it("counts only what is both kept and applicable", () => {
        const excluded = toggleAskConnexProposalExclusion(new Set<number>(), 52);
        const [group] = askConnexProposalGroups([first, second, blocked], actionable, excluded);

        expect([...group.included]).toEqual([51, 53]);
        expect(group.applicable).toBe(2);
        expect(group.selected).toBe(1);
        expect([...toggleAskConnexProposalExclusion(excluded, 52)]).toEqual([]);
    });

    it("leaves a proposal whose record moved out of the batch the button commits to", () => {
        const moved = { ...second, change: change({ field: "stage", state: "recordChanged" }) };
        const [group] = askConnexProposalGroups([first, moved, blocked], actionable, new Set());
        const markup = render(
            <AskConnexProposalReview
                group={group}
                labels={reviewLabels}
                cardLabels={cardLabels}
                actionsDisabled={false}
                onToggleInclusion={() => {}}
                onAction={() => {}}
                onApplySelected={() => {}}
            />,
        );

        expect(group.applicable).toBe(1);
        expect(group.selected).toBe(1);
        expect(markup).toContain("1 of 3 can be applied now.");
        expect(markup).toContain("Apply 1 changes");
        expect(markup).toContain(cardLabels.changeState.recordChanged);
    });

    it("shows every record, value, and reason in the full review", () => {
        const [group] = askConnexProposalGroups(
            [first, second, blocked], actionable, new Set([52]),
        );
        const markup = render(
            <AskConnexProposalReview
                group={group}
                labels={reviewLabels}
                cardLabels={cardLabels}
                actionsDisabled={false}
                onToggleInclusion={() => {}}
                onAction={() => {}}
                onApplySelected={() => {}}
            />,
        );

        expect(markup).toContain("3 changes need your review");
        expect(markup).toContain("2 of 3 can be applied now.");
        expect(markup).toContain("Acme renewal");
        expect(markup).toContain("Globex expansion");
        expect(markup).toContain("Initech pilot");
        expect(markup).toContain("Negotiation");
        expect(markup).toContain("Won");
        expect(markup).toContain("You no longer have permission to make this change.");
        expect(markup).toContain("Include the change to Globex expansion");
        expect(markup).toContain("1 changes selected");
        expect(markup).toContain("Apply 1 changes");
    });

    it("says so plainly when nothing in the batch can be applied", () => {
        const [group] = askConnexProposalGroups(
            [
                blocked,
                { ...first, change: change({ state: "unresolved" }) },
                { ...second, change: change({ field: "stage", state: "unchanged" }) },
            ],
            actionable,
            new Set(),
        );
        const markup = render(
            <AskConnexProposalReview
                group={group}
                labels={reviewLabels}
                cardLabels={cardLabels}
                actionsDisabled={false}
                onToggleInclusion={() => {}}
                onAction={() => {}}
                onApplySelected={() => {}}
            />,
        );

        expect(group.selected).toBe(0);
        expect(markup).toContain("None of these can be applied right now.");
        expect(markup).toContain("Apply 0 changes");
        expect(markup).toContain("disabled");
    });

    it("announces the batch in the drawer and hands the review to the full workspace", () => {
        const [group] = askConnexProposalGroups(
            [first, second, blocked], actionable, new Set(),
        );
        const markup = render(
            <AskConnexProposalReviewSummary
                group={group}
                labels={reviewLabels}
                onOpenFullView={() => {}}
            />,
        );

        expect(markup).toContain("3 changes need your review");
        expect(markup).toContain("2 of 3 can be applied now.");
        expect(markup).toContain("Open review in full view");
        expect(markup).not.toContain("Include the change to Acme renewal");
        expect(markup).not.toContain("Apply 2 changes");
    });
});
