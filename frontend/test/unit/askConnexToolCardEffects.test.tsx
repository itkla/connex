import { act, type AnchorHTMLAttributes, type PropsWithChildren } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import AskConnexProposalReview from "@/app/components/ask-connex/AskConnexProposalReview";
import AskConnexToolCard from "@/app/components/ask-connex/AskConnexToolCard";
import { NowProvider } from "@/app/hooks/useNow";
import {
    askConnexProposalGroups,
    askConnexToolCardAffordances,
    toggleAskConnexProposalExclusion,
    type AskConnexToolAction,
    type AskConnexToolCardState,
} from "@/app/lib/askConnex";
import { observeProposalReviewClock, proposalReviewNow } from "@/app/lib/proposalReviewClock";
import {
    askConnexCard,
    askConnexCardLabels,
    askConnexChange,
    askConnexReviewLabels,
} from "@/test/unit/helpers/askConnexToolCards";
import {
    installInteractiveDocument,
    type InteractiveElement,
} from "@/test/unit/helpers/interactiveDocument";

vi.mock("next/link", async () => {
    const React = await import("react");
    type LinkProps = PropsWithChildren<AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }>;
    return {
        default: ({ children, href, ...props }: LinkProps) =>
            React.createElement("a", { ...props, href }, children),
    };
});

/**
 * The shared checkbox, stubbed to its checked/disabled contract.
 *
 * Radix's real checkbox measures and syncs against a hidden native input that the node test
 * environment cannot lay out. What this file proves is the review behaviour Connex owns — which
 * rows are still selectable after a batch, and what toggling one does — not the third-party
 * control underneath it.
 */
vi.mock("@/components/ui/checkbox", async () => {
    const React = await import("react");
    return {
        Checkbox: ({
            checked = false,
            disabled = false,
            onCheckedChange,
            ...props
        }: {
            checked?: boolean;
            disabled?: boolean;
            onCheckedChange?: (checked: boolean) => void;
            "aria-label"?: string;
            className?: string;
        }) => React.createElement("button", {
            type: "button",
            role: "checkbox",
            "aria-label": props["aria-label"],
            "aria-checked": checked ? "true" : "false",
            "aria-disabled": disabled ? "true" : "false",
            onClick: () => {
                if (!disabled) onCheckedChange?.(!checked);
            },
        }),
    };
});

/**
 * The server response and render timestamp, rounded to HTTP Date precision.
 *
 * Proposal tests advance monotonic elapsed time independently of the device clock. Undo retains
 * its wall-clock timer, so its deadline must also sit in the real future.
 */
const NOW = Math.floor(Date.now() / 1_000) * 1_000;
const UNDO_DEADLINE = new Date(NOW + 600_000).toISOString();

type Interactive = ReturnType<typeof installInteractiveDocument>;

function requiredElement(
    elements: InteractiveElement[],
    predicate: (element: InteractiveElement) => boolean,
    what: string,
): InteractiveElement {
    const found = elements.find(predicate);
    if (!found) throw new Error(`${what} was not rendered`);
    return found;
}

function attached(element: InteractiveElement): boolean {
    let current: InteractiveElement = element;
    while (current.parentNode !== null) current = current.parentNode;
    return current.tagName === "HTML" || current.tagName === "BODY";
}

function present(elements: InteractiveElement[], text: string): boolean {
    return elements.some(
        (element) => attached(element) && element.textContent.includes(text),
    );
}

function control(interactive: Interactive, label: string): InteractiveElement {
    return requiredElement(
        interactive.elements,
        (element) => attached(element) && element.getAttribute("aria-label") === label,
        `Control "${label}"`,
    );
}

/**
 * Mounts one component into the interactive document, with its timers in this test's hands.
 *
 * The card schedules the close of a real undo window minutes into the future. Waiting for it would
 * make the suite take minutes; skipping it would leave the one behaviour the card owns — retiring
 * its own offer the moment the server's deadline passes — untested. The captured handlers are run
 * by the test at the point the deadline would really have arrived.
 */
async function mount(node: React.ReactNode): Promise<{
    interactive: Interactive;
    root: { render: (next: React.ReactNode) => void; unmount: () => void };
    rerender: (next: React.ReactNode) => Promise<void>;
    scheduled: Array<{ handler: () => void; delay: number | undefined }>;
    resume: (type: 'focus' | 'visibilitychange') => void;
}> {
    const interactive = installInteractiveDocument();
    observeProposalReviewClock(new Headers({ Date: new Date(NOW).toUTCString() }));
    const documentEvents = vi.spyOn(document, 'addEventListener');
    const scheduled: Array<{ handler: () => void; delay: number | undefined }> = [];
    Object.assign(window, {
        setTimeout: (handler: () => void, delay?: number) => {
            scheduled.push({ handler, delay });
            return scheduled.length;
        },
        clearTimeout: () => {},
    });
    const { createRoot } = await import("react-dom/client");
    const root = createRoot(interactive.container);
    const wrap = (next: React.ReactNode) => (
        <NowProvider value={NOW}>{next}</NowProvider>
    );
    await act(async () => {
        root.render(wrap(node));
    });
    return {
        interactive,
        root,
        scheduled,
        resume: (type) => {
            const subscriptions = type === 'focus'
                ? vi.mocked(window.addEventListener).mock.calls
                : documentEvents.mock.calls;
            for (const [eventType, listener] of subscriptions) {
                if (eventType !== type) continue;
                if (typeof listener === 'function') listener(new Event(type));
                else listener.handleEvent(new Event(type));
            }
        },
        rerender: async (next: React.ReactNode) => {
            await act(async () => {
                root.render(wrap(next));
            });
        },
    };
}

const EXECUTED = askConnexCard({
    reviewExpiresAt: new Date(NOW + 72 * 3_600_000).toISOString(),
    id: 41,
    toolName: "create_task",
    tier: "auto",
    status: "executed",
    requestSummary: "Create a task",
    outcomeSummary: "Task created",
    outcomeValues: [{ field: "description", value: "Follow up with Kenji Sato" }],
    createdRecord: { kind: "task", id: 74 },
    undoExpiresAt: UNDO_DEADLINE,
    undoAvailable: true,
    executedAt: new Date(NOW).toISOString(),
});

function renderedCard(
    card: AskConnexToolCardState,
    onAction: (toolCallId: number, action: AskConnexToolAction) => void,
) {
    return (
        <AskConnexToolCard
            card={card}
            labels={askConnexCardLabels}
            actionsDisabled={false}
            onAction={onAction}
            formatDeadline={(instant) => `deadline(${instant})`}
            formatRemaining={(instant) => `remaining(${instant})`}
        />
    );
}

describe("assistant tool card undo window", () => {
    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it("retires its own undo offer the moment the server deadline passes", async () => {
        const { interactive, root, scheduled } = await mount(
            renderedCard(EXECUTED, () => {}),
        );

        expect(present(interactive.elements, "You can undo this until")).toBe(true);
        expect(scheduled).toHaveLength(1);

        await act(async () => {
            const close = scheduled[0];
            if (close) close.handler();
        });

        expect(present(interactive.elements, "You can undo this until")).toBe(false);
        expect(present(interactive.elements, "Undo expired")).toBe(true);
        expect(interactive.elements.some(
            (element) => attached(element)
                && element.getAttribute("aria-label")
                    === "Undo the assistant action for Acme renewal",
        )).toBe(false);

        await act(async () => root.unmount());
    });

    it("drops the sentence with the control when the server withdraws the capability", async () => {
        const { interactive, root, rerender } = await mount(
            renderedCard(EXECUTED, () => {}),
        );

        expect(present(interactive.elements, "You can undo this until")).toBe(true);

        await rerender(renderedCard({ ...EXECUTED, undoAvailable: false }, () => {}));

        expect(present(interactive.elements, "You can undo this until")).toBe(false);

        await act(async () => root.unmount());
    });
});

describe("assistant tool card focus after a decision", () => {
    afterEach(() => {
        vi.unstubAllGlobals();
    });

    const proposal = askConnexCard({
        id: 51, change: askConnexChange(), reviewExpiresAt: UNDO_DEADLINE,
    });

    it("returns a keyboard reader to the card their own pressed control left", async () => {
        const { interactive, root, rerender } = await mount(
            renderedCard(proposal, () => {}),
        );
        const apply = control(interactive, "Apply the proposed change to Acme renewal");
        apply.focus();

        await act(async () => {
            interactive.dispatch("click", apply);
        });
        await rerender(renderedCard({ ...proposal, pendingAction: "approve" }, () => {}));
        await rerender(renderedCard({ ...proposal, status: "rejected" }, () => {}));

        const article = requiredElement(
            interactive.elements,
            (element) => element.tagName === "ARTICLE" && attached(element),
            "Tool card",
        );
        expect(Object.is(document.activeElement, article)).toBe(true);

        await act(async () => root.unmount());
    });

    it("answers a refused decision on the alert that explains it", async () => {
        const { interactive, root, rerender } = await mount(
            renderedCard(proposal, () => {}),
        );
        const apply = control(interactive, "Apply the proposed change to Acme renewal");
        apply.focus();

        await act(async () => {
            interactive.dispatch("click", apply);
        });
        await rerender(renderedCard({ ...proposal, pendingAction: "approve" }, () => {}));
        await rerender(
            renderedCard({ ...proposal, failure: "proposalChanged" }, () => {}),
        );

        const alert = requiredElement(
            interactive.elements,
            (element) => element.getAttribute("role") === "alert" && attached(element),
            "Failure alert",
        );
        expect(alert.textContent).toContain("The target changed after this proposal was shown.");
        expect(Object.is(document.activeElement, alert)).toBe(true);

        await act(async () => root.unmount());
    });

    it("leaves a reader who moved on where they went", async () => {
        const { interactive, root, rerender } = await mount(
            renderedCard(proposal, () => {}),
        );
        const apply = control(interactive, "Apply the proposed change to Acme renewal");
        apply.focus();

        await act(async () => {
            interactive.dispatch("click", apply);
        });
        await rerender(renderedCard({ ...proposal, pendingAction: "approve" }, () => {}));
        const composer = document.createElement("textarea");
        document.body?.appendChild(composer);
        composer.focus();

        await rerender(renderedCard({ ...proposal, status: "rejected" }, () => {}));

        expect(Object.is(document.activeElement, composer)).toBe(true);

        await act(async () => root.unmount());
    });

    it("never moves a reader for a refresh they did not ask for", async () => {
        const { interactive, root, rerender } = await mount(
            renderedCard({ ...proposal, pendingAction: "approve" }, () => {}),
        );
        const elsewhere = document.createElement("textarea");
        document.body?.appendChild(elsewhere);
        elsewhere.focus();

        await rerender(renderedCard({ ...proposal, pendingAction: null }, () => {}));

        expect(Object.is(document.activeElement, elsewhere)).toBe(true);
        expect(interactive.elements.length).toBeGreaterThan(0);

        await act(async () => root.unmount());
    });
});

describe("grouped review interaction", () => {
    afterEach(() => {
        vi.unstubAllGlobals();
    });

    const first = askConnexCard({
        id: 51, change: askConnexChange(), reviewExpiresAt: UNDO_DEADLINE,
    });
    const second = askConnexCard({
        reviewExpiresAt: UNDO_DEADLINE,
        id: 52,
        target: { kind: "deal", id: 8, label: "Globex expansion" },
        requestSummary: "Change deal stage to: Won",
        toolName: "change_deal_stage",
        change: askConnexChange({
            field: "stage",
            currentValue: "Negotiation",
            proposedValue: "Won",
        }),
    });
    const third = askConnexCard({
        reviewExpiresAt: UNDO_DEADLINE,
        id: 53,
        target: { kind: "deal", id: 9, label: "Initech pilot" },
        change: askConnexChange(),
    });
    const actionable = new Set([51, 52, 53, 54]);

    function review(
        cards: readonly AskConnexToolCardState[],
        excluded: ReadonlySet<number>,
        onToggleInclusion: (toolCallId: number) => void,
    ) {
        const [group] = askConnexProposalGroups(cards, actionable, excluded, NOW);
        if (!group) throw new Error("Expected a grouped review");
        return (
            <AskConnexProposalReview
                group={group}
                labels={askConnexReviewLabels}
                cardLabels={askConnexCardLabels}
                actionsDisabled={false}
                onToggleInclusion={onToggleInclusion}
                onAction={() => {}}
                onApplySelected={async () => {}}
            />
        );
    }

    it("takes a row out of the batch and puts it back, counting each time", async () => {
        let excluded: ReadonlySet<number> = new Set<number>();
        const toggle = (toolCallId: number) => {
            excluded = toggleAskConnexProposalExclusion(excluded, toolCallId);
        };
        const cards = [first, second, third];
        const { interactive, root, rerender } = await mount(
            review(cards, excluded, toggle),
        );

        expect(present(interactive.elements, "3 changes selected")).toBe(true);

        await act(async () => {
            interactive.dispatch(
                "click",
                control(interactive, "Include the change to Globex expansion"),
            );
        });
        await rerender(review(cards, excluded, toggle));

        expect([...excluded]).toEqual([52]);
        expect(present(interactive.elements, "2 changes selected")).toBe(true);
        expect(present(interactive.elements, "Apply 2 changes")).toBe(true);

        await act(async () => {
            interactive.dispatch(
                "click",
                control(interactive, "Include the change to Globex expansion"),
            );
        });
        await rerender(review(cards, excluded, toggle));

        expect([...excluded]).toEqual([]);
        expect(present(interactive.elements, "3 changes selected")).toBe(true);

        await act(async () => root.unmount());
    });

    it("shows a half-failed batch as what it was, on the rows it failed on", async () => {
        const applied = askConnexCard({
            id: 54, status: "executed", reviewExpiresAt: UNDO_DEADLINE,
        });
        const { interactive, root } = await mount(review(
            [
                first,
                { ...second, failure: "proposalChanged" },
                { ...third, failure: "proposalPermissionLost" },
                applied,
            ],
            new Set<number>(),
            () => {},
        ));

        expect(present(interactive.elements, "1 applied")).toBe(true);
        expect(present(interactive.elements, "2 could not be applied")).toBe(true);
        expect(present(interactive.elements, "1 changes selected")).toBe(true);
        expect(interactive.elements.filter(
            (element) => element.getAttribute("role") === "alert" && attached(element),
        )).toHaveLength(2);
        expect(
            control(interactive, "Include the change to Globex expansion")
                .getAttribute("aria-checked"),
        ).toBe("false");
        expect(
            control(interactive, "Include the change to Acme renewal")
                .getAttribute("aria-checked"),
        ).toBe("true");

        await act(async () => root.unmount());
    });
});

describe("proposal review deadline", () => {
    afterEach(() => {
        vi.restoreAllMocks();
        vi.unstubAllGlobals();
    });

    it.each([-3_600_000, 3_600_000].flatMap((skew) =>
        (['timer', 'focus', 'visibilitychange'] as const).map((trigger) => ({ skew, trigger }))))(
        "clientDisarmsAtServerDeadlineWithoutNetwork: $trigger with device skew $skew",
        async ({ skew, trigger }) => {
            const deadline = NOW + 1_000;
            vi.spyOn(Date, 'now').mockReturnValue(NOW + skew);
            const elapsed = vi.spyOn(performance, 'now').mockReturnValue(0);
            const action = vi.fn();
            const proposal = askConnexCard({
                change: askConnexChange(),
                reviewExpiresAt: new Date(deadline).toISOString(),
            });
            const { interactive, root, scheduled, resume } = await mount(renderedCard(proposal, action));
            expect(control(interactive, "Apply the proposed change to Acme renewal")).toBeDefined();
            expect(control(interactive, "Discard the proposed change to Acme renewal")).toBeDefined();
            expect(scheduled).toHaveLength(1);
            const timer = scheduled[0];
            if (!timer) throw new Error('Missing proposal deadline timer');
            expect(timer.delay).toBe(deadline - NOW);
            expect(askConnexToolCardAffordances(proposal, proposalReviewNow())).toEqual(['reject', 'approve']);

            elapsed.mockReturnValue(999);
            expect(askConnexToolCardAffordances(proposal, proposalReviewNow())).toEqual(['reject', 'approve']);
            elapsed.mockReturnValue(1_000);
            await act(async () => {
                if (trigger === 'timer') {
                    timer.handler();
                } else resume(trigger);
            });

            const actions = interactive.elements.filter((element) => attached(element)
                && ['Apply the proposed change to Acme renewal', 'Discard the proposed change to Acme renewal']
                    .includes(element.getAttribute('aria-label') ?? ''));
            expect(actions).toEqual([]);
            expect(present(interactive.elements, 'Proposal expired')).toBe(true);
            expect(present(interactive.elements, askConnexCardLabels.proposalExpired)).toBe(true);
            expect(action).not.toHaveBeenCalled();
            expect(askConnexToolCardAffordances(proposal, proposalReviewNow())).toEqual([]);
            await act(async () => root.unmount());
        },
    );

    it('reschedules a mounted deadline when a later response recalibrates server time', async () => {
        vi.spyOn(performance, 'now').mockReturnValue(0);
        const proposal = askConnexCard({
            change: askConnexChange(), reviewExpiresAt: new Date(NOW + 10_000).toISOString(),
        });
        const { interactive, root, scheduled } = await mount(renderedCard(proposal, () => {}));
        expect(scheduled.at(-1)?.delay).toBe(10_000);

        await act(async () => {
            observeProposalReviewClock(new Headers({ Date: new Date(NOW + 8_000).toUTCString() }));
        });
        expect(scheduled.at(-1)?.delay).toBe(2_000);
        expect(control(interactive, "Apply the proposed change to Acme renewal")).toBeDefined();

        await act(async () => {
            observeProposalReviewClock(new Headers({ Date: new Date(NOW + 10_000).toUTCString() }));
        });
        expect(present(interactive.elements, 'Proposal expired')).toBe(true);
        await act(async () => root.unmount());
    });
});
