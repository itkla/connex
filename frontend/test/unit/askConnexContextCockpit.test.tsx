import { act, createRef, type RefObject } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";

import {
    AskConnexContextStrip,
    AskConnexScopeNotice,
    type AskConnexContextLabels,
} from "@/app/components/ask-connex/AskConnexContextCockpit";
import {
    askConnexScopeNeedsConfirmation,
    askConnexScopePreview,
    askConnexScopePreviewKey,
    isAskConnexPinned,
    mergeAskConnexContext,
    parseStoredAskConnexPins,
    serializeAskConnexPins,
    toggleAskConnexPin,
    type AskConnexAttachment,
    type AskConnexFileAttachment,
    type AskConnexSelectionContext,
} from "@/app/lib/askConnex";
import {
    installInteractiveDocument,
    type InteractiveElement,
} from "@/test/unit/helpers/interactiveDocument";

const labels: AskConnexContextLabels = {
    context: "Context",
    contextFile: "File",
    contextLimit: "You can include up to 10 records and files.",
    contextMentioned: "Mentioned",
    contextPage: "Page",
    contextPinned: "Kept",
    contextReset: "Put back what I removed",
    contextSelected: (count, type) => `${count} selected ${type}`,
    contextScopeUnsupported: "This all-matching scope is not sent yet.",
    contextUnavailable: "Unavailable",
    contextUnsupported: (type) => `${type} context is not sent to Ask Connex yet.`,
    pinContext: (label) => `Keep ${label} here while I move around`,
    removeContext: (label) => `Remove ${label} from context`,
    removeFile: (label) => `Remove ${label}`,
    scopeConfirm: "Ask with this context",
    scopeEdit: "Change the context",
    scopeSummary: (preview) => `I'll read ${preview.total} records and ${preview.files} files.`,
    scopeTitle: "What this will cover",
    unpinContext: (label) => `Stop keeping ${label} here`,
    uploadProgress: (progress) => `${progress}%`,
    uploadRemoving: "Removing…",
};

const AIKO: AskConnexAttachment = { kind: "person", id: 42, label: "Aiko Tanaka" };
const ACME: AskConnexAttachment = { kind: "company", id: 7, label: "Acme" };

const NOOP_REF: RefObject<HTMLDivElement | null> = { current: null };

function strip(overrides: Partial<Parameters<typeof AskConnexContextStrip>[0]> = {}) {
    return (
        <AskConnexContextStrip
            groupRef={NOOP_REF}
            implicitContext={null}
            pinnedContext={[]}
            pageContextPinned={false}
            selectionContext={null}
            unsupportedPageContext={null}
            attachments={[]}
            fileAttachments={[]}
            canRemoveFiles
            fileOperationPending={false}
            overflow={false}
            corrected={false}
            labels={labels}
            onRemove={() => {}}
            onRemoveFile={() => {}}
            onTogglePagePin={() => {}}
            onUnpin={() => {}}
            onRemovePage={() => {}}
            onRemoveSelection={() => {}}
            onReset={() => {}}
            {...overrides}
        />
    );
}

function readyFile(fileName: string): AskConnexFileAttachment {
    return {
        clientId: "file-1",
        id: 3,
        fileName,
        contentType: "text/plain",
        size: 2048,
        kind: "text",
        status: "ready",
        progress: 100,
        error: null,
    };
}

function selection(
    overrides: Partial<AskConnexSelectionContext> = {},
): AskConnexSelectionContext {
    return {
        type: "deal",
        count: 12,
        available: true,
        unavailableReason: null,
        pageContext: [],
        ...overrides,
    };
}

function requiredElement(
    elements: InteractiveElement[],
    predicate: (element: InteractiveElement) => boolean,
    what: string,
): InteractiveElement {
    const found = elements.find(predicate);
    if (!found) throw new Error(`${what} was not rendered`);
    return found;
}

function byLabel(elements: InteractiveElement[], label: string): InteractiveElement {
    return requiredElement(
        elements,
        (element) => element.getAttribute("aria-label") === label,
        `Control labelled "${label}"`,
    );
}

describe("context cockpit states", () => {
    it("renders nothing when the request carries no context and nothing was corrected", () => {
        expect(renderToStaticMarkup(strip())).toBe("");
    });

    it("names the current record as page context and offers pin and remove", () => {
        const markup = renderToStaticMarkup(strip({ implicitContext: AIKO }));

        expect(markup).toContain(">Page<");
        expect(markup).toContain("Aiko Tanaka");
        expect(markup).toContain('aria-label="Keep Aiko Tanaka here while I move around"');
        expect(markup).toContain('aria-label="Remove Aiko Tanaka from context"');
    });

    it("offers unpin instead of pin once the page record is kept", () => {
        const markup = renderToStaticMarkup(
            strip({ implicitContext: AIKO, pageContextPinned: true }),
        );

        expect(markup).toContain('aria-label="Stop keeping Aiko Tanaka here"');
        expect(markup).toContain('aria-pressed="true"');
        expect(markup).not.toContain("Keep Aiko Tanaka here while I move around");
    });

    it("shows kept records as their own chip with a way to stop keeping them", () => {
        const markup = renderToStaticMarkup(strip({ pinnedContext: [ACME] }));

        expect(markup).toContain(">Kept<");
        expect(markup).toContain("Acme");
        expect(markup).toContain('aria-label="Stop keeping Acme here"');
    });

    it("shows mentioned records with a way to take them out", () => {
        const markup = renderToStaticMarkup(strip({ attachments: [ACME] }));

        expect(markup).toContain(">Mentioned<");
        expect(markup).toContain('aria-label="Remove Acme from context"');
    });

    it("shows a selection with its count, its type, and a way to drop it", () => {
        const markup = renderToStaticMarkup(strip({ selectionContext: selection() }));

        expect(markup).toContain("12 selected deal");
        expect(markup).toContain('aria-label="Remove 12 selected deal from context"');
    });

    it("shows attached files with their size and a way to remove them", () => {
        const markup = renderToStaticMarkup(strip({ fileAttachments: [readyFile("notes.txt")] }));

        expect(markup).toContain(">File<");
        expect(markup).toContain("notes.txt");
        expect(markup).toContain('aria-label="Remove notes.txt"');
    });

    it("keeps an unsupported page record visible and says it is not sent", () => {
        const markup = renderToStaticMarkup(strip({
            unsupportedPageContext: { type: "note", label: "Kickoff notes" },
        }));

        expect(markup).toContain(">Unavailable<");
        expect(markup).toContain("Kickoff notes");
        expect(markup).toContain("note context is not sent to Ask Connex yet.");
    });

    it("explains an all-matching selection without claiming the loaded rows", () => {
        const markup = renderToStaticMarkup(strip({
            selectionContext: selection({ available: false, unavailableReason: "scope" }),
        }));

        expect(markup).toContain("This all-matching scope is not sent yet.");
        expect(markup).not.toContain("is not sent to Ask Connex yet.");
    });

    it("explains an unsupported selection type from that selection, not a stand-in", () => {
        const markup = renderToStaticMarkup(strip({
            selectionContext: selection({
                type: "task",
                available: false,
                unavailableReason: "record_type",
            }),
        }));

        expect(markup).toContain("task context is not sent to Ask Connex yet.");
    });

    it("reports the record cap instead of silently truncating", () => {
        const markup = renderToStaticMarkup(strip({ implicitContext: AIKO, overflow: true }));

        expect(markup).toContain("You can include up to 10 records and files.");
        expect(markup).toContain('role="alert"');
    });

    it("offers a way back only after something inferred was taken out", () => {
        expect(renderToStaticMarkup(strip({ implicitContext: AIKO })))
            .not.toContain("Put back what I removed");
        expect(renderToStaticMarkup(strip({ corrected: true })))
            .toContain("Put back what I removed");
    });

    it("disables a file control while its upload or removal is still settling", () => {
        const markup = renderToStaticMarkup(strip({
            fileAttachments: [{ ...readyFile("notes.txt"), status: "uploading", progress: 40 }],
        }));

        expect(markup).toContain("disabled");
        expect(markup).toContain("40%");
    });
});

describe("correcting context", () => {
    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it("pins, unpins, removes, and puts back through the chip controls", async () => {
        const interactive = installInteractiveDocument();
        const { createRoot } = await import("react-dom/client");
        const root = createRoot(interactive.container);
        const events: string[] = [];

        await act(async () => {
            root.render(strip({
                implicitContext: AIKO,
                pinnedContext: [ACME],
                selectionContext: selection(),
                corrected: true,
                onTogglePagePin: () => events.push("pin"),
                onUnpin: (attachment) => events.push(`unpin:${attachment.label}`),
                onRemovePage: () => events.push("removePage"),
                onRemoveSelection: () => events.push("removeSelection"),
                onReset: () => events.push("reset"),
            }));
        });

        for (const label of [
            "Keep Aiko Tanaka here while I move around",
            "Stop keeping Acme here",
            "Remove Aiko Tanaka from context",
            "Remove 12 selected deal from context",
        ]) {
            await act(async () => {
                interactive.dispatch("click", byLabel(interactive.elements, label));
            });
        }

        const reset = requiredElement(
            interactive.elements,
            (element) => element.tagName === "BUTTON"
                && element.textContent.includes("Put back what I removed"),
            "Reset control",
        );
        await act(async () => {
            interactive.dispatch("click", reset);
        });

        expect(events).toEqual([
            "pin",
            "unpin:Acme",
            "removePage",
            "removeSelection",
            "reset",
        ]);

        await act(async () => root.unmount());
    });

    it("gives the strip a focus target so a scope check can hand the user back to it", async () => {
        const interactive = installInteractiveDocument();
        const { createRoot } = await import("react-dom/client");
        const root = createRoot(interactive.container);
        const groupRef = createRef<HTMLDivElement>();

        await act(async () => {
            root.render(strip({ groupRef, implicitContext: AIKO }));
        });

        const group = requiredElement(
            interactive.elements,
            (element) => element.getAttribute("role") === "group",
            "Context group",
        );
        expect(group.getAttribute("tabindex")).toBe("-1");
        expect(group.getAttribute("aria-label")).toBe("Context");

        await act(async () => root.unmount());
    });
});

describe("interpreted scope", () => {
    it("stays quiet until a request carries more than a subject and its comparisons", () => {
        const narrow = Array.from({ length: 4 }, (_, index) => ({
            kind: "deal" as const,
            id: index + 1,
        }));

        expect(askConnexScopePreview(narrow, 0)).toBeNull();
        expect(askConnexScopePreview([...narrow, { kind: "deal", id: 5 }], 0)).not.toBeNull();
    });

    it("counts only the records the request will actually carry", () => {
        const preview = askConnexScopePreview(
            [
                { kind: "deal", id: 1 },
                { kind: "deal", id: 2 },
                { kind: "deal", id: 3 },
                { kind: "person", id: 4 },
                { kind: "person", id: 5 },
            ],
            2,
        );

        expect(preview).toEqual({
            total: 5,
            records: [
                { kind: "deal", count: 3 },
                { kind: "person", count: 2 },
            ],
            files: 2,
        });
    });

    it("re-asks when the scope changes and not when it is unchanged", () => {
        const first = askConnexScopePreviewKey(askConnexScopePreview(
            Array.from({ length: 5 }, (_, index) => ({ kind: "deal" as const, id: index + 1 })),
            0,
        ));
        const wider = askConnexScopePreviewKey(askConnexScopePreview(
            Array.from({ length: 6 }, (_, index) => ({ kind: "deal" as const, id: index + 1 })),
            0,
        ));

        expect(askConnexScopeNeedsConfirmation(first, null)).toBe(true);
        expect(askConnexScopeNeedsConfirmation(first, first)).toBe(false);
        expect(askConnexScopeNeedsConfirmation(wider, first)).toBe(true);
        expect(askConnexScopeNeedsConfirmation(null, null)).toBe(false);
    });

    it("states what it will read and offers both agreeing and correcting", () => {
        const preview = askConnexScopePreview(
            Array.from({ length: 5 }, (_, index) => ({ kind: "deal" as const, id: index + 1 })),
            1,
        );
        if (preview === null) throw new Error("expected a scope preview");
        const markup = renderToStaticMarkup(
            <AskConnexScopeNotice
                preview={preview}
                labels={labels}
                onConfirm={() => {}}
                onEdit={() => {}}
            />,
        );

        expect(markup).toContain("I&#x27;ll read 5 records and 1 files.");
        expect(markup).toContain("Ask with this context");
        expect(markup).toContain("Change the context");
        expect(markup).toContain('aria-label="What this will cover"');
    });

    it("announces the held scope, and only the scope, when the request waits", () => {
        const preview = askConnexScopePreview(
            Array.from({ length: 5 }, (_, index) => ({ kind: "deal" as const, id: index + 1 })),
            1,
        );
        if (preview === null) throw new Error("expected a scope preview");
        const markup = renderToStaticMarkup(
            <AskConnexScopeNotice
                preview={preview}
                labels={labels}
                onConfirm={() => {}}
                onEdit={() => {}}
            />,
        );
        const announced = markup.slice(markup.indexOf('role="status"'));
        const summaryEnd = announced.indexOf("</p>");

        expect(summaryEnd).toBeGreaterThan(0);
        expect(announced.slice(0, summaryEnd)).toContain("I&#x27;ll read 5 records and 1 files.");
        expect(announced.indexOf("Ask with this context")).toBeGreaterThan(summaryEnd);
    });

    it("runs the request only once the user agrees to its scope", async () => {
        const interactive = installInteractiveDocument();
        const { createRoot } = await import("react-dom/client");
        const root = createRoot(interactive.container);
        const preview = askConnexScopePreview(
            Array.from({ length: 5 }, (_, index) => ({ kind: "deal" as const, id: index + 1 })),
            0,
        );
        if (preview === null) throw new Error("expected a scope preview");
        const events: string[] = [];

        await act(async () => {
            root.render(
                <AskConnexScopeNotice
                    preview={preview}
                    labels={labels}
                    onConfirm={() => events.push("confirm")}
                    onEdit={() => events.push("edit")}
                />,
            );
        });

        for (const text of ["Change the context", "Ask with this context"]) {
            const control = requiredElement(
                interactive.elements,
                (element) => element.tagName === "BUTTON" && element.textContent.includes(text),
                `Scope control "${text}"`,
            );
            await act(async () => {
                interactive.dispatch("click", control);
            });
        }

        expect(events).toEqual(["edit", "confirm"]);

        await act(async () => root.unmount());
    });
});

describe("corrected context reaches the request", () => {
    it("drops a removed page record and a removed selection from what is sent", () => {
        const record = { type: "person" as const, id: 42, label: "Aiko Tanaka" };
        const rows = {
            type: "deal" as const,
            ids: new Set([1, 2]),
            sourceSurface: "record_list" as const,
            scope: { kind: "explicit_selection" as const, recordIds: [1, 2] },
        };

        expect(mergeAskConnexContext(record, "", rows).pageContext).toEqual([
            { kind: "person", id: 42 },
            { kind: "deal", id: 1 },
            { kind: "deal", id: 2 },
        ]);
        expect(mergeAskConnexContext(record, "", rows, {
            pageDismissed: true,
            selectionDismissed: true,
            pinned: [],
        }).pageContext).toEqual([]);
    });

    it("carries kept records even when the page contributes nothing", () => {
        expect(mergeAskConnexContext(null, "", null, {
            pageDismissed: false,
            selectionDismissed: false,
            pinned: [ACME],
        }).pageContext).toEqual([{ kind: "company", id: 7 }]);
    });

    it("never sends a kept record twice when it is also the page record", () => {
        expect(mergeAskConnexContext(
            { type: "company", id: 7, label: "Acme" },
            "",
            null,
            { pageDismissed: false, selectionDismissed: false, pinned: [ACME] },
        ).pageContext).toEqual([{ kind: "company", id: 7 }]);
    });

    it("still refuses to substitute loaded rows for an all-matching selection", () => {
        const rows = {
            type: "company" as const,
            ids: new Set([3, 4]),
            sourceSurface: "record_list" as const,
            scope: { kind: "filter_match" as const, filter: { industry: ["Software"] } },
        };

        expect(mergeAskConnexContext(null, "", rows, {
            pageDismissed: false,
            selectionDismissed: false,
            pinned: [],
        }).pageContext).toEqual([]);
    });
});

describe("kept records survive navigation", () => {
    it("adds, removes, and recognises a pin", () => {
        expect(toggleAskConnexPin([], AIKO)).toEqual([AIKO]);
        expect(toggleAskConnexPin([AIKO], AIKO)).toEqual([]);
        expect(toggleAskConnexPin([AIKO], ACME)).toEqual([ACME, AIKO]);
        expect(isAskConnexPinned([AIKO], AIKO)).toBe(true);
        expect(isAskConnexPinned([AIKO], ACME)).toBe(false);
        expect(isAskConnexPinned([AIKO], null)).toBe(false);
    });

    it("never keeps more records than one request may carry", () => {
        const many = Array.from({ length: 10 }, (_, index) => ({
            kind: "deal" as const,
            id: index + 1,
            label: `Deal ${index + 1}`,
        }));
        const pins = many.reduce<AskConnexAttachment[]>(
            (current, attachment) => toggleAskConnexPin(current, attachment),
            [],
        );

        expect(pins).toHaveLength(10);
        expect(toggleAskConnexPin(pins, { kind: "person", id: 99, label: "New" })).toHaveLength(10);
    });

    it("round-trips through storage and drops anything malformed", () => {
        expect(parseStoredAskConnexPins(serializeAskConnexPins([AIKO, ACME]))).toEqual([AIKO, ACME]);
        expect(parseStoredAskConnexPins(null)).toEqual([]);
        expect(parseStoredAskConnexPins("not json")).toEqual([]);
        expect(parseStoredAskConnexPins('{"kind":"person"}')).toEqual([]);
        expect(parseStoredAskConnexPins(JSON.stringify([
            { kind: "task", id: 1, label: "Call" },
            { kind: "person", id: 0, label: "Zero" },
            { kind: "person", id: 5, label: "  " },
            { kind: "person", id: 42, label: " Aiko Tanaka " },
            { kind: "person", id: 42, label: "Duplicate" },
        ]))).toEqual([AIKO]);
    });
});
