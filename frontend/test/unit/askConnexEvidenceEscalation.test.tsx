import { act, type AnchorHTMLAttributes, type PropsWithChildren, type ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { AskConnexEvidenceMarker } from "@/app/components/ask-connex/AskConnexEvidence";
import type { AskConnexAnswerDocumentLabels } from "@/app/components/ask-connex/answerDocument";
import type { AiChatCitation } from "@/app/lib/types";
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
 * The anchored popup, stubbed down to its open/close contract. Base UI's real popover needs
 * `floating-ui` layout measurement that the node test environment cannot provide; what this file
 * proves is the escalation Connex owns — which surface is showing at each step — not the third-party
 * positioning underneath it.
 */
vi.mock("@/components/ui/popover", async () => {
    const React = await import("react");
    type OverlayState = { open: boolean; setOpen: (open: boolean) => void };
    const OverlayContext = React.createContext<OverlayState>({ open: false, setOpen: () => {} });
    return {
        Popover: ({
            open = false,
            onOpenChange,
            children,
        }: {
            open?: boolean;
            onOpenChange?: (open: boolean) => void;
            children?: ReactNode;
        }) => React.createElement(
            OverlayContext.Provider,
            { value: { open, setOpen: onOpenChange ?? (() => {}) } },
            children,
        ),
        PopoverTrigger: ({ children, ...props }: PropsWithChildren<Record<string, unknown>>) => {
            const overlay = React.useContext(OverlayContext);
            return React.createElement(
                "button",
                {
                    ...props,
                    "aria-expanded": overlay.open,
                    onClick: () => overlay.setOpen(!overlay.open),
                },
                children,
            );
        },
        PopoverContent: ({ children }: PropsWithChildren) => {
            const overlay = React.useContext(OverlayContext);
            return overlay.open ? React.createElement("div", { "data-peek": "" }, children) : null;
        },
        PopoverTitle: ({ children }: PropsWithChildren) => React.createElement("p", null, children),
        PopoverDescription: ({ children }: PropsWithChildren) => React.createElement("p", null, children),
        PopoverClose: ({ children }: PropsWithChildren) => React.createElement("button", null, children),
    };
});

/** The shared responsive dialog, stubbed to the same open/close contract for the same reason. */
vi.mock("@/components/ui/responsive-dialog", async () => {
    const React = await import("react");
    type OverlayState = { open: boolean; setOpen: (open: boolean) => void };
    const OverlayContext = React.createContext<OverlayState>({ open: false, setOpen: () => {} });
    return {
        ResponsiveDialog: ({
            open = false,
            onOpenChange,
            onCloseComplete,
            children,
        }: {
            open?: boolean;
            onOpenChange?: (open: boolean) => void;
            onCloseComplete?: () => void;
            children?: ReactNode;
        }) => React.createElement(
            OverlayContext.Provider,
            {
                value: {
                    open,
                    setOpen: (next: boolean) => {
                        onOpenChange?.(next);
                        if (!next) onCloseComplete?.();
                    },
                },
            },
            children,
        ),
        ResponsiveDialogContent: ({ children }: PropsWithChildren) => {
            const overlay = React.useContext(OverlayContext);
            return overlay.open
                ? React.createElement("div", { role: "dialog", "data-inspector": "" }, children)
                : null;
        },
        ResponsiveDialogHeader: ({ children }: PropsWithChildren) => React.createElement("div", null, children),
        ResponsiveDialogFooter: ({ children }: PropsWithChildren) => React.createElement("div", null, children),
        ResponsiveDialogTitle: ({ children }: PropsWithChildren) => React.createElement("h2", null, children),
        ResponsiveDialogDescription: ({ children }: PropsWithChildren) => React.createElement("p", null, children),
        ResponsiveDialogClose: ({ asChild, children }: PropsWithChildren<{ asChild?: boolean }>) => {
            const overlay = React.useContext(OverlayContext);
            const close = { "data-inspector-close": "", onClick: () => overlay.setOpen(false) };
            if (asChild && React.isValidElement(children)) {
                return React.cloneElement(children, close);
            }
            return React.createElement("button", { type: "button", ...close }, children);
        },
    };
});

const labels: AskConnexAnswerDocumentLabels = {
    absoluteTime: (instant) => `abs(${instant})`,
    citationKind: (kind) => `citationKind:${kind}`,
    dismiss: "Close",
    evidence: "Evidence",
    evidenceDetail: "Excerpt",
    freshness: "Freshness",
    freshnessCurrent: "Record updated",
    moreDetail: "More detail",
    openRecord: "Open record",
    relativeTime: (instant) => `rel(${instant})`,
    sourceLimits: "Source limits",
    unsupported: "No source for this — read it as unconfirmed.",
};

const CITATION: AiChatCitation = {
    handle: "h1",
    kind: "person",
    id: 42,
    label: "Aiko Tanaka",
    asOf: "2026-08-01T09:00:00Z",
    detail: "Met at the Osaka review",
};

function requiredElement(
    elements: InteractiveElement[],
    predicate: (element: InteractiveElement) => boolean,
    what: string,
): InteractiveElement {
    const found = elements.find(predicate);
    if (!found) throw new Error(`${what} was not rendered`);
    return found;
}

/** Whether the node is still in the rendered tree rather than in a detached, unmounted subtree. */
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

describe("evidence escalation", () => {
    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it("escalates marker to peek to inspector and back, returning focus to the marker", async () => {
        const interactive = installInteractiveDocument();
        const { createRoot } = await import("react-dom/client");
        const root = createRoot(interactive.container);

        await act(async () => {
            root.render(
                <AskConnexEvidenceMarker
                    citation={CITATION}
                    caveats={["Results were bounded"]}
                    labels={labels}
                />,
            );
        });

        const marker = requiredElement(
            interactive.elements,
            (element) => element.tagName === "BUTTON" && element.textContent.includes("Aiko Tanaka"),
            "Evidence marker",
        );
        expect(marker.getAttribute("type")).toBe("button");
        expect(marker.getAttribute("aria-expanded")).toBe("false");
        expect(present(interactive.elements, "More detail")).toBe(false);

        await act(async () => {
            interactive.dispatch("click", marker);
        });
        expect(marker.getAttribute("aria-expanded")).toBe("true");
        expect(present(interactive.elements, "Met at the Osaka review")).toBe(true);
        expect(present(interactive.elements, "rel(2026-08-01T09:00:00Z)")).toBe(true);

        const escalate = requiredElement(
            interactive.elements,
            (element) => element.tagName === "BUTTON" && element.textContent.includes("More detail"),
            "Peek escalation control",
        );
        await act(async () => {
            interactive.dispatch("click", escalate);
        });

        expect(marker.getAttribute("aria-expanded")).toBe("false");
        expect(present(interactive.elements, "More detail")).toBe(false);
        expect(present(interactive.elements, "Excerpt")).toBe(true);
        expect(present(interactive.elements, "Source limits")).toBe(true);
        expect(present(interactive.elements, "Results were bounded")).toBe(true);

        const openRecord = requiredElement(
            interactive.elements,
            (element) => element.tagName === "A" && element.textContent.includes("Open record"),
            "Inspector record link",
        );
        expect(openRecord.getAttribute("href")).toBe("/records/contacts/42");

        const dismiss = requiredElement(
            interactive.elements,
            (element) => element.getAttribute("data-inspector-close") !== null,
            "Inspector dismiss control",
        );
        await act(async () => {
            interactive.dispatch("click", dismiss);
        });

        expect(present(interactive.elements, "Excerpt")).toBe(false);
        expect(interactive.elements.some(
            (element) => element.getAttribute("data-inspector") !== null && attached(element),
        )).toBe(false);
        expect(Object.is(document.activeElement, marker)).toBe(true);

        await act(async () => root.unmount());
    });

    it("reopens the peek from the marker after the inspector was dismissed", async () => {
        const interactive = installInteractiveDocument();
        const { createRoot } = await import("react-dom/client");
        const root = createRoot(interactive.container);

        await act(async () => {
            root.render(
                <AskConnexEvidenceMarker citation={CITATION} caveats={[]} labels={labels} />,
            );
        });
        const marker = requiredElement(
            interactive.elements,
            (element) => element.tagName === "BUTTON" && element.textContent.includes("Aiko Tanaka"),
            "Evidence marker",
        );

        await act(async () => {
            interactive.dispatch("click", marker);
        });
        await act(async () => {
            interactive.dispatch("click", marker);
        });
        expect(marker.getAttribute("aria-expanded")).toBe("false");
        expect(present(interactive.elements, "More detail")).toBe(false);

        await act(async () => {
            interactive.dispatch("click", marker);
        });
        expect(present(interactive.elements, "More detail")).toBe(true);

        await act(async () => root.unmount());
    });
});

describe("opening the record leaves the evidence surface behind", () => {
    afterEach(() => {
        vi.unstubAllGlobals();
    });

    async function openMarker(): Promise<{
        interactive: ReturnType<typeof installInteractiveDocument>;
        root: { unmount: () => void };
        marker: InteractiveElement;
    }> {
        const interactive = installInteractiveDocument();
        const { createRoot } = await import("react-dom/client");
        const root = createRoot(interactive.container);
        await act(async () => {
            root.render(
                <AskConnexEvidenceMarker citation={CITATION} caveats={[]} labels={labels} />,
            );
        });
        const marker = requiredElement(
            interactive.elements,
            (element) => element.tagName === "BUTTON" && element.textContent.includes("Aiko Tanaka"),
            "Evidence marker",
        );
        await act(async () => {
            interactive.dispatch("click", marker);
        });
        return { interactive, root, marker };
    }

    it("dismisses the peek when its record link is followed", async () => {
        const { interactive, root, marker } = await openMarker();
        const openRecord = requiredElement(
            interactive.elements,
            (element) => attached(element)
                && element.tagName === "A"
                && element.textContent.includes("Open record"),
            "Peek record link",
        );

        await act(async () => {
            interactive.dispatch("click", openRecord);
        });

        expect(marker.getAttribute("aria-expanded")).toBe("false");
        expect(present(interactive.elements, "More detail")).toBe(false);

        await act(async () => root.unmount());
    });

    it("dismisses the inspector when its record link is followed", async () => {
        const { interactive, root } = await openMarker();
        const escalate = requiredElement(
            interactive.elements,
            (element) => element.tagName === "BUTTON" && element.textContent.includes("More detail"),
            "Peek escalation control",
        );
        await act(async () => {
            interactive.dispatch("click", escalate);
        });
        const openRecord = requiredElement(
            interactive.elements,
            (element) => attached(element)
                && element.tagName === "A"
                && element.textContent.includes("Open record"),
            "Inspector record link",
        );

        await act(async () => {
            interactive.dispatch("click", openRecord);
        });

        expect(present(interactive.elements, "Excerpt")).toBe(false);
        expect(interactive.elements.some(
            (element) => element.getAttribute("data-inspector") !== null && attached(element),
        )).toBe(false);

        await act(async () => root.unmount());
    });
});
