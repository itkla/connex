import { act, type PropsWithChildren, type ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { NextIntlClientProvider } from "next-intl";
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
    assistantAuthor: "Connex",
    partialAnswer: "Unfinished answer — this is as far as the assistant got before it stopped.",
    continueFromPartial: "Continue from here",
    narrowScope: "Narrow what it covers",
    retry: "Try again",
    stop: "Stop generating",
    stopping: "Stopping…",
    terminalMessage: {
        generic: "The assistant could not finish this answer.",
        breadthSteps: "Too many records for one pass.",
        breadthResults: "Too much relationship data at once.",
        skillBudget: "Found what it needed but ran out of room to write it up.",
        toolAuthority: "Ask Connex could not complete this request.",
        budget: "Daily AI limit reached.",
        capacity: "No AI capacity left right now.",
        workspaceDisabled: "Ask Connex is turned off for this workspace.",
        contextWindowTooSmall: "The configured AI model has too small a context window.",
        accessRevoked: "Your access to something this answer was reading changed.",
        restrictionsChanged: "The AI processing restrictions for this workspace changed.",
        imageUnsupported: "This assistant model cannot read images.",
        provider: "The AI provider did not answer this request.",
        internal: "Something went wrong on our side.",
        unreadable: "The answer came back in a shape Connex could not read.",
        stalled: "It kept re-reading the same records without getting any further.",
        timeout: "This answer ran out of time before it finished.",
    },
    turnAccepted: "Request accepted",
    turnCancelled: "Response stopped",
    turnResolved: "Answer ready",
    turnStreaming: "Writing…",
    turnWorking: "Thinking…",
};

/** The Markdown vocabulary the streamed tail's renderer reads from `next-intl`. */
const MARKDOWN_MESSAGES = {
    ActivityNotesEditor: {
        taskChecked: "Completed checklist item",
        taskUnchecked: "Incomplete checklist item",
        calloutInfo: "Information callout",
        calloutSuccess: "Success callout",
        calloutWarning: "Warning callout",
        calloutDanger: "Danger callout",
    },
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

function withIntl(node: ReactNode): ReactNode {
    return (
        <NextIntlClientProvider locale="en" messages={MARKDOWN_MESSAGES}>
            {node}
        </NextIntlClientProvider>
    );
}

function tail(phase: AskConnexTurnState["phase"], text = PARTIAL_TEXT): Promise<Rendered> {
    const store = createAskConnexStreamStore();
    store.publish({ turnId: 9, text });
    return renderClient(
        withIntl(<StreamingTail store={store} turn={turn({ phase })} labels={labels} />),
    );
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
            withIntl(<StreamingTail store={store} turn={turn({ phase: "resolved" })} labels={labels} />),
        );
        expect(rendered.text).toBe("");
    });

    it("renders no tail for an answer other than the one on screen", async () => {
        const store = createAskConnexStreamStore();
        store.publish({ turnId: 8, text: "Stale" });
        const rendered = await renderClient(
            withIntl(<StreamingTail store={store} turn={turn({ phase: "running" })} labels={labels} />),
        );
        expect(rendered.text).toBe("");
    });
});

describe("what a stopped answer offers next", () => {
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
        const named: [string, string][] = [
            ["budget_exhausted", labels.terminalMessage.budget],
            ["quota_exhausted", labels.terminalMessage.capacity],
            ["image_input_unsupported", labels.terminalMessage.imageUnsupported],
            ["skill_budget_exceeded", labels.terminalMessage.skillBudget],
            ["tool_outside_skill_authority", labels.terminalMessage.toolAuthority],
            ["workspace_disabled", labels.terminalMessage.workspaceDisabled],
            ["access_revoked", labels.terminalMessage.accessRevoked],
            ["restrictions_changed", labels.terminalMessage.restrictionsChanged],
        ];
        for (const [reason, message] of named) {
            expect(activity({ turn: turn({ phase: "failed", reason }) })).toContain(message);
        }
    });

    it("explains a reason it has never heard of without inventing advice for it", () => {
        const rendered = activity({
            turn: turn({ phase: "failed", reason: "a_reason_from_a_later_release" }),
            hasPartial: true,
        });
        expect(rendered).toContain(labels.terminalMessage.generic);
        expect(rendered).not.toContain(labels.narrowScope);
        expect(rendered).toContain(labels.retry);
    });

    it("announces the outcome without reading the offered routes aloud", () => {
        const markup = activity();
        const status = markup.slice(markup.indexOf('role="status"'));
        const announced = status.slice(0, status.indexOf("</div>"));
        expect(announced.length).toBeGreaterThan(0);
        expect(announced).toContain(labels.terminalMessage.generic);
        expect(announced).not.toContain("Try again");
    });

    it("announces the status line while the answer is still being produced", () => {
        const markup = activity({
            turn: turn({ phase: "running", progress: PROGRESS, cancellable: true }),
            streaming: true,
        });
        const status = markup.indexOf('role="status"');
        expect(status).toBeGreaterThanOrEqual(0);
        expect(markup).toContain("Writing…");
        expect(markup).toContain("Stop generating");
    });

    it("says it is thinking before any words have streamed", () => {
        const markup = activity({
            turn: turn({ phase: "running", progress: PROGRESS }),
            streaming: false,
        });
        expect(markup).toContain(labels.turnWorking);
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

describe("recovery routes a stopped answer offers", () => {
    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it("offers continuing alongside retry on an ordinary failure", () => {
        const rendered = activity({ hasPartial: true });
        expect(rendered).toContain(labels.retry);
        expect(rendered).toContain(labels.continueFromPartial);
        expect(rendered).not.toContain(labels.narrowScope);
    });

    it("withholds continuing when the answer left no words behind", () => {
        const rendered = activity({ hasPartial: false });
        expect(rendered).toContain(labels.retry);
        expect(rendered).not.toContain(labels.continueFromPartial);
        expect(rendered).not.toContain(labels.narrowScope);
    });

    it("leads with narrowing and offers no route that re-asks a request that was too broad", () => {
        const rendered = activity({
            turn: turn({ phase: "failed", reason: "step_cap_exceeded", progress: PROGRESS }),
            hasPartial: true,
        });
        expect(rendered).toContain(labels.terminalMessage.breadthSteps);
        expect(rendered).not.toContain(labels.terminalMessage.generic);
        expect(rendered).not.toContain(labels.retry);
        expect(rendered).not.toContain(labels.continueFromPartial);
        expect(rendered).toContain(labels.narrowScope);
    });

    it("offers nothing to build on a partial the member may no longer read", () => {
        for (const reason of ["access_revoked", "restrictions_changed"]) {
            const rendered = activity({
                turn: turn({ phase: "failed", reason, progress: PROGRESS }),
                hasPartial: true,
            });
            expect(rendered).not.toContain(labels.retry);
            expect(rendered).not.toContain(labels.continueFromPartial);
            expect(rendered).not.toContain(labels.narrowScope);
        }
    });

    it("does not offer an immediate retry of a request that had no capacity to run", () => {
        const rendered = activity({
            turn: turn({ phase: "failed", reason: "budget_exhausted", progress: PROGRESS }),
            hasPartial: true,
        });
        expect(rendered).toContain(labels.terminalMessage.budget);
        expect(rendered).not.toContain(labels.retry);
        expect(rendered).not.toContain(labels.continueFromPartial);
        expect(rendered).not.toContain(labels.narrowScope);
    });

    it("offers nothing on an answer the member stopped themselves", () => {
        const rendered = activity({
            turn: turn({ phase: "cancelled", progress: PROGRESS }),
            hasPartial: true,
        });
        expect(rendered).toContain(labels.turnCancelled);
        expect(rendered).not.toContain(labels.retry);
        expect(rendered).not.toContain(labels.continueFromPartial);
        expect(rendered).not.toContain(labels.narrowScope);
    });

    async function press(reason: string | null, label: string): Promise<string[]> {
        const interactive = installInteractiveDocument();
        const { createRoot } = await import("react-dom/client");
        const root = createRoot(interactive.container);
        const pressed: string[] = [];

        await act(async () => {
            root.render(
                <TurnActivity
                    turn={turn({ phase: "failed", reason, progress: PROGRESS })}
                    streaming={false}
                    cancelling={false}
                    canRetry
                    hasPartial
                    labels={labels}
                    onCancel={() => {}}
                    onRetry={() => pressed.push("retry")}
                    onContinueFromPartial={() => pressed.push("continue")}
                    onNarrowScope={() => pressed.push("narrow")}
                />,
            );
        });

        const control = interactive.elements.find(
            (element: InteractiveElement) => element.tagName === "BUTTON"
                && element.textContent.includes(label),
        );
        if (!control) throw new Error(`the ${label} control was not rendered`);
        await act(async () => {
            interactive.dispatch("click", control);
        });
        await act(async () => {
            root.unmount();
        });
        return pressed;
    }

    it("runs the narrow handler when a request that was too broad offers it", async () => {
        expect(await press("step_cap_exceeded", labels.narrowScope)).toEqual(["narrow"]);
    });

    it("runs the continue handler when an ordinary failure offers it", async () => {
        expect(await press("request_failed", labels.continueFromPartial)).toEqual(["continue"]);
    });
});
