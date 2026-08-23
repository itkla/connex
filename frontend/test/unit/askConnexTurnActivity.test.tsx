import { act, type PropsWithChildren, type ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";

import {
    StreamingTail,
    TurnActivity,
    type AskConnexTurnLabels,
} from "@/app/components/ask-connex/AskConnexDrawer";
import { createAskConnexStreamStore } from "@/app/lib/askConnexStream";
import { EMPTY_ASK_CONNEX_TURN, type AskConnexTurnState } from "@/app/lib/askConnex";
import type { AiChatProgressItem } from "@/app/lib/types";
import { TooltipProvider } from "@/components/ui/tooltip";
import {
    installInteractiveDocument,
    type InteractiveElement,
} from "@/test/unit/helpers/interactiveDocument";

/**
 * The scroller item, stubbed to a plain container. It exists to register a node with the scroll
 * controller, which needs layout measurement the node test environment cannot provide; what this
 * file proves is what a settled answer keeps, says, and offers — not where it scrolls to.
 */
vi.mock("@/components/ui/message-scroller", async () => {
    const React = await import("react");
    return {
        MessageScrollerItem: ({ children }: PropsWithChildren<Record<string, unknown>>) =>
            React.createElement("div", null, children),
    };
});

const labels: AskConnexTurnLabels = {
    answerDocument: {
        absoluteTime: (instant) => `abs(${instant})`,
        blockKind: (kind) => `kind:${kind}`,
        citationKind: (kind) => `citationKind:${kind}`,
        comparisonAgainst: "Compared with",
        comparisonValue: "Value",
        copyDraft: "Copy",
        copyDraftDone: "Copied",
        coverage: "Coverage",
        coverageStatus: (status) => `coverageStatus:${status}`,
        diffAfter: "After",
        diffBefore: "Before",
        dismiss: "Close",
        evidence: "Evidence",
        evidenceDetail: "Excerpt",
        exclusions: "Not included",
        exclusion: (exclusion) => `exclusion:${exclusion}`,
        freshness: "Freshness",
        freshnessCurrent: "Record updated",
        moreDetail: "More detail",
        openRecord: "Open record",
        period: (start, end) => `period ${start} to ${end}`,
        progressCount: (count) => `(${count} items)`,
        progressSource: (source) => `progressSource:${source}`,
        progressStatus: (status) => `progressStatus:${status}`,
        relativeTime: (instant) => `rel(${instant})`,
        sourceLimits: "Source limits",
        sources: "Sources checked",
        source: (source) => `source:${source}`,
        truncated: "Results were bounded",
        unsupported: "No source for this — read it as unconfirmed.",
        whatChecked: "What I checked",
    },
    assistantAuthor: "Connex",
    budgetExhausted: "Daily AI limit reached.",
    toolResultBudgetExhausted: "Too much relationship data at once.",
    partialAnswer: "Unfinished answer — this is as far as the assistant got before it stopped.",
    retry: "Try again",
    stop: "Stop generating",
    stopping: "Stopping…",
    turnAccepted: "Request accepted",
    turnCancelled: "Response stopped",
    turnFailed: "The assistant could not finish this answer.",
    turnImageUnsupported: "This assistant model cannot read images.",
    turnResolved: "Answer ready",
    turnStreaming: "Writing…",
    turnTimedOut: "This answer timed out before it could finish.",
    turnWorking: "Checking trusted sources…",
};

const PROGRESS: AiChatProgressItem[] = [
    { seq: 1, source: "scope", status: "complete", count: null, truncated: false },
    { seq: 2, source: "deals", status: "complete", count: 12, truncated: true },
    { seq: 3, source: "metrics", status: "failed", count: null, truncated: false },
];

const SETTLED_WITHOUT_ANSWER = ["failed", "timed_out", "cancelled"] as const;
const PARTIAL_TEXT = "Two deals are cooling";

type Rendered = { text: string; classes: string };

function turn(overrides: Partial<AskConnexTurnState> = {}): AskConnexTurnState {
    return { ...EMPTY_ASK_CONNEX_TURN, sessionId: 4, turnId: 9, ...overrides };
}

/**
 * Renders through a client root rather than to static markup: the streamed tail reads its text from
 * an external store whose server snapshot is deliberately empty, so a server render can never show
 * what a real reader sees.
 */
async function renderClient(node: ReactNode): Promise<Rendered> {
    const interactive = installInteractiveDocument();
    const { createRoot } = await import("react-dom/client");
    const root = createRoot(interactive.container);
    await act(async () => {
        root.render(node);
    });
    const rendered: Rendered = {
        text: interactive.container.textContent,
        classes: interactive.elements
            .map((element: InteractiveElement) => element.getAttribute("class") ?? "")
            .join(" "),
    };
    await act(async () => {
        root.unmount();
    });
    return rendered;
}

function tail(phase: AskConnexTurnState["phase"], text = PARTIAL_TEXT): Promise<Rendered> {
    const store = createAskConnexStreamStore();
    store.publish({ turnId: 9, text });
    return renderClient(<StreamingTail store={store} turn={turn({ phase })} labels={labels} />);
}

function activity(overrides: Partial<Parameters<typeof TurnActivity>[0]> = {}): string {
    return renderToStaticMarkup(
        <TooltipProvider>
            <TurnActivity
                turn={turn({ phase: "failed", progress: PROGRESS })}
                streaming={false}
                cancelling={false}
                canRetry
                labels={labels}
                onCancel={() => {}}
                onRetry={() => {}}
                {...overrides}
            />
        </TooltipProvider>,
    );
}

describe("partial answers that stopped", () => {
    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it("keeps the written words on screen after the answer stops without finishing", async () => {
        for (const phase of SETTLED_WITHOUT_ANSWER) {
            expect((await tail(phase)).text).toContain(PARTIAL_TEXT);
        }
    });

    it("says plainly that what is shown is unfinished", async () => {
        for (const phase of SETTLED_WITHOUT_ANSWER) {
            expect((await tail(phase)).text).toContain(labels.partialAnswer);
        }
    });

    it("does not call a still-running answer unfinished", async () => {
        for (const phase of ["accepted", "running"] as const) {
            const rendered = await tail(phase);
            expect(rendered.text).toContain(PARTIAL_TEXT);
            expect(rendered.text).not.toContain(labels.partialAnswer);
            expect(rendered.classes).toContain("animate-caret-blink");
        }
    });

    it("renders no tail for a finished answer, whose words are the transcript message", async () => {
        const store = createAskConnexStreamStore();
        store.publish(null);
        const rendered = await renderClient(
            <StreamingTail store={store} turn={turn({ phase: "resolved" })} labels={labels} />,
        );
        expect(rendered.text).toBe("");
    });

    it("renders no tail for an answer other than the one on screen", async () => {
        const store = createAskConnexStreamStore();
        store.publish({ turnId: 8, text: "Stale" });
        const rendered = await renderClient(
            <StreamingTail store={store} turn={turn({ phase: "running" })} labels={labels} />,
        );
        expect(rendered.text).toBe("");
    });
});

describe("what a stopped answer offers next", () => {
    it("keeps what was checked readable after the answer stopped", () => {
        for (const phase of SETTLED_WITHOUT_ANSWER) {
            const markup = activity({ turn: turn({ phase, progress: PROGRESS }) });
            expect(markup).toContain("What I checked");
            expect(markup).toContain("progressSource:deals");
            expect(markup).toContain("(12 items)");
            expect(markup).toContain("progressStatus:failed");
        }
    });

    it("offers to send the same question again after a failure or a timeout", () => {
        for (const phase of ["failed", "timed_out"] as const) {
            expect(activity({ turn: turn({ phase, progress: PROGRESS }) })).toContain("Try again");
        }
    });

    it("does not press the member to retry what they chose to stop", () => {
        expect(activity({ turn: turn({ phase: "cancelled", progress: PROGRESS }) }))
            .not.toContain("Try again");
    });

    it("hides the retry control when there is nothing that could be sent again", () => {
        expect(activity({ canRetry: false })).not.toContain("Try again");
    });

    it("names the specific reason a failure gives up on", () => {
        expect(activity({ turn: turn({ phase: "failed", reason: "budget_exhausted" }) }))
            .toContain(labels.budgetExhausted);
        expect(activity({ turn: turn({ phase: "failed", reason: "image_input_unsupported" }) }))
            .toContain(labels.turnImageUnsupported);
    });

    it("announces the outcome without reading the whole trail aloud", () => {
        const markup = activity();
        const status = markup.slice(markup.indexOf('role="status"'));
        const announced = status.slice(0, status.indexOf("</div>"));
        expect(announced.length).toBeGreaterThan(0);
        expect(announced).toContain(labels.turnFailed);
        expect(announced).not.toContain("What I checked");
        expect(announced).not.toContain("Try again");
    });

    it("announces each milestone while the answer is still being produced", () => {
        const markup = activity({
            turn: turn({ phase: "running", progress: PROGRESS, cancellable: true }),
            streaming: true,
        });
        const status = markup.indexOf('role="status"');
        expect(status).toBeGreaterThanOrEqual(0);
        expect(markup.indexOf("What I checked")).toBeGreaterThan(status);
        expect(markup).toContain("Writing…");
        expect(markup).toContain("Stop generating");
    });

    it("offers the stop control only to the member whose turn it is", () => {
        const watched = activity({
            turn: turn({ phase: "running", progress: PROGRESS }),
            streaming: true,
        });

        expect(watched).toContain("Writing…");
        expect(watched).not.toContain("Stop generating");
    });
});

describe("retry sends the question again", () => {
    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it("runs the retry handler when the control is pressed", async () => {
        const interactive = installInteractiveDocument();
        const { createRoot } = await import("react-dom/client");
        const root = createRoot(interactive.container);
        const retries: number[] = [];

        await act(async () => {
            root.render(
                <TurnActivity
                    turn={turn({ phase: "failed", progress: PROGRESS })}
                    streaming={false}
                    cancelling={false}
                    canRetry
                    labels={labels}
                    onCancel={() => {}}
                    onRetry={() => retries.push(1)}
                />,
            );
        });

        const control = interactive.elements.find(
            (element: InteractiveElement) => element.tagName === "BUTTON"
                && element.textContent.includes("Try again"),
        );
        if (!control) throw new Error("the retry control was not rendered");
        await act(async () => {
            interactive.dispatch("click", control);
        });

        expect(retries).toEqual([1]);
        await act(async () => {
            root.unmount();
        });
    });
});
