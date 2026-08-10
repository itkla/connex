import {
    createElement,
    isValidElement,
    type ComponentProps,
    type PropsWithChildren,
    type ReactNode,
} from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { submitDealDraftUpdates } from "@/app/components/records/deals/DealsBrowser";
import { submitDealDraftUpdate } from "@/app/components/records/deals/EditDealSheet";
import QuickEditDealSheet, { type DealDraft } from "@/app/components/records/deals/QuickEditDealSheet";
import { actualValueForOutcome } from "@/app/components/records/deals/dealOutcome";
import type { Deal, Pipeline, Stage } from "@/app/lib/types";

const { updateDealMock } = vi.hoisted(() => ({
    updateDealMock: vi.fn(),
}));

vi.mock("react", async () => {
    const React = await vi.importActual<typeof import("react")>("react");
    return {
        ...React,
        useEffect: () => undefined,
    };
});

vi.mock("@/app/lib/api", async () => {
    const actual = await vi.importActual<typeof import("@/app/lib/api")>("@/app/lib/api");
    return {
        ...actual,
        updateDeal: updateDealMock,
    };
});

vi.mock("next-intl", () => ({
    useTranslations: () => (key: string) => key,
}));

vi.mock("motion/react", async () => {
    const React = await import("react");
    type MotionDivProps = ComponentProps<"div"> & {
        animate?: unknown;
        exit?: unknown;
        initial?: unknown;
        transition?: unknown;
    };
    return {
        AnimatePresence: ({ children }: PropsWithChildren) => children,
        motion: {
            div: ({ children, className }: MotionDivProps) =>
                React.createElement("div", { className }, children),
        },
        useReducedMotion: () => true,
    };
});

vi.mock("@/app/components/activity/notes/MentionEditor", () => ({
    default: () => null,
}));

vi.mock("@/app/components/records/quick-edit/QuickEditSheetShell", async () => {
    const React = await import("react");
    type FieldProps = PropsWithChildren<{ htmlFor?: string; label: ReactNode }>;
    return {
        EASE_OUT: [0.23, 1, 0.32, 1],
        QuickEditField: ({ children, htmlFor, label }: FieldProps) => React.createElement(
            "div",
            null,
            React.createElement("label", { htmlFor }, label),
            children,
        ),
        QuickEditRecordCard: ({ children }: PropsWithChildren) => React.createElement("article", null, children),
        QuickEditSheetShell: ({ children }: PropsWithChildren) => React.createElement("section", null, children),
    };
});

vi.mock("@/app/hooks/useCompanySearch", () => ({
    useCompanySearch: () => ({
        companies: [],
        error: null,
        loading: false,
        onInputValueChange: () => undefined,
    }),
}));

vi.mock("@/components/ui/combobox", async () => {
    const React = await import("react");
    const Container = ({ children }: PropsWithChildren) => React.createElement("div", null, children);
    return {
        Combobox: Container,
        ComboboxContent: Container,
        ComboboxEmpty: Container,
        ComboboxInput: () => null,
        ComboboxItem: Container,
        ComboboxList: Container,
    };
});

const LOST_DEAL = {
    id: 7,
    name: "Lost renewal",
    value: 500,
    actualValue: 275,
    currency: "USD",
    pipeline: null,
    stage: null,
    position: 0,
    company: null,
    won: false,
    createdAt: "2026-08-01T00:00:00Z",
    updatedAt: "2026-08-01T00:00:00Z",
} satisfies Deal;

const LOST_DRAFT = {
    name: LOST_DEAL.name,
    value: LOST_DEAL.value,
    actualValue: LOST_DEAL.actualValue,
    currency: LOST_DEAL.currency,
    pipeline: 0,
    stage: 0,
    company: null,
    expectedCloseDate: "",
    closedAt: "2026-08-01 00:00:00",
    closedReason: null,
    won: false,
} satisfies DealDraft;

const WON_LINE_ITEM_DEAL = {
    ...LOST_DEAL,
    name: "Won renewal",
    valueSource: "line_items",
    pipeline: 3,
    stage: 11,
    won: true,
} satisfies Deal;

const WON_LINE_ITEM_DRAFT = {
    ...LOST_DRAFT,
    name: WON_LINE_ITEM_DEAL.name,
    pipeline: 3,
    stage: 11,
    won: true,
} satisfies DealDraft;

const PIPELINE = {
    id: 3,
    name: "Sales",
    createdAt: "2026-08-01T00:00:00Z",
    updatedAt: "2026-08-01T00:00:00Z",
} satisfies Pipeline;

const WON_STAGE = {
    id: 11,
    name: "Closed won",
    pipeline: PIPELINE.id,
    position: 0,
    success: true,
    failure: false,
} satisfies Stage;

const LOST_STAGE = {
    id: 12,
    name: "Closed lost",
    pipeline: PIPELINE.id,
    position: 1,
    success: false,
    failure: true,
} satisfies Stage;

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null;
}

function isUnknownFunction(value: unknown): value is (...args: unknown[]) => unknown {
    return typeof value === "function";
}

function findElementFunction(
    root: unknown,
    matches: (type: unknown, props: Record<string, unknown>) => boolean,
    propName: string,
    missingMessage: string,
): (...args: unknown[]) => unknown {
    let callback: ((...args: unknown[]) => unknown) | undefined;
    const visit = (node: unknown) => {
        if (Array.isArray(node)) {
            for (const child of node) visit(child);
            return;
        }
        if (!isRecord(node) || !isValidElement(node) || !isRecord(node.props)) return;
        const children = node.props.children;
        const candidate = node.props[propName];
        if (matches(node.type, node.props) && isUnknownFunction(candidate)) {
            callback = candidate;
        }
        visit(children);
    };
    visit(root);
    if (!callback) throw new Error(missingMessage);
    return callback;
}

function findOutcomeButton(root: unknown, label: string): () => unknown {
    const onClick = findElementFunction(
        root,
        (type, props) => type === "button" && props.children === label,
        "onClick",
        `Outcome button ${label} was not rendered`,
    );
    return () => onClick();
}

function findFailureStageSelector(root: unknown): (stage: Stage) => unknown {
    const onValueChange = findElementFunction(
        root,
        (_type, props) => {
            const items = props.items;
            return Array.isArray(items) && items.some((item) => isRecord(item) && item.failure === true);
        },
        "onValueChange",
        "Failure-stage selector was not rendered",
    );
    return (stage) => onValueChange(stage);
}

function createOutcomeDraftHarness() {
    let draft: DealDraft = { ...WON_LINE_ITEM_DRAFT };
    const props = {
        open: true,
        onOpenChange: () => undefined,
        selectedIds: new Set([WON_LINE_ITEM_DEAL.id]),
        selectedDeals: [WON_LINE_ITEM_DEAL],
        drafts: { [WON_LINE_ITEM_DEAL.id]: draft },
        updateDraft: (_id: number, patch: Partial<DealDraft>) => {
            draft = { ...draft, ...patch };
        },
        pipelines: [PIPELINE],
        stagesByPipeline: { [PIPELINE.id]: [WON_STAGE, LOST_STAGE] },
        isSaving: false,
        saveEdits: () => undefined,
    } satisfies ComponentProps<typeof QuickEditDealSheet>;

    return {
        getDraft: () => draft,
        selectOutcome: (outcome: "Open" | "Won" | "Lost") => {
            const tree = QuickEditDealSheet({
                ...props,
                drafts: { [WON_LINE_ITEM_DEAL.id]: draft },
            });
            findOutcomeButton(tree, `outcome${outcome}`)();
        },
        selectFailureStage: () => {
            const tree = QuickEditDealSheet({
                ...props,
                drafts: { [WON_LINE_ITEM_DEAL.id]: draft },
            });
            findFailureStageSelector(tree)(LOST_STAGE);
        },
    };
}

beforeEach(() => {
    updateDealMock.mockReset().mockResolvedValue(WON_LINE_ITEM_DEAL);
});

describe("lost-deal actual value", () => {
    function renderActualValueInput(won: boolean, actualValue: number) {
        const html = renderToStaticMarkup(createElement(QuickEditDealSheet, {
            open: true,
            onOpenChange: () => undefined,
            selectedIds: new Set([LOST_DEAL.id]),
            selectedDeals: [{ ...LOST_DEAL, won, actualValue }],
            drafts: { [LOST_DEAL.id]: { ...LOST_DRAFT, won, actualValue } },
            updateDraft: () => undefined,
            pipelines: [],
            stagesByPipeline: {},
            isSaving: false,
            saveEdits: () => undefined,
        }));
        const input = html.match(/<input\b[^>]*id="deal-actual-value-7"[^>]*>/)?.[0];
        return { html, input };
    }

    it("renders the realized-value input disabled at zero with an explanation", () => {
        const { html, input } = renderActualValueInput(false, 275);

        expect(input).toBeDefined();
        expect(input).toContain("disabled=\"\"");
        expect(input).toContain("value=\"0\"");
        expect(input).toContain("aria-describedby=\"deal-actual-value-hint-7\"");
        expect(html).toContain("actualValueLost");
    });

    it("keeps the realized-value input editable for a won deal", () => {
        const { html, input } = renderActualValueInput(true, 275);

        expect(input).toBeDefined();
        expect(input).not.toContain("disabled=\"\"");
        expect(input).toContain("value=\"275\"");
        expect(input).not.toContain("aria-describedby");
        expect(html).not.toContain("actualValueLost");
    });

    it("canonicalizes lost values to zero without discarding won or open values", () => {
        expect(actualValueForOutcome(false, 275)).toBe(0);
        expect(actualValueForOutcome(true, 275)).toBe(275);
        expect(actualValueForOutcome(null, 275)).toBe(275);
    });

    it("preserves realized value when a won deal is toggled through lost back to won before submit", async () => {
        const form = createOutcomeDraftHarness();

        form.selectOutcome("Lost");
        expect(form.getDraft().actualValue).toBe(WON_LINE_ITEM_DEAL.actualValue);
        form.selectOutcome("Won");
        await submitDealDraftUpdate(WON_LINE_ITEM_DEAL.id, form.getDraft());

        expect(updateDealMock).toHaveBeenCalledWith(
            WON_LINE_ITEM_DEAL.id,
            expect.objectContaining({ actualValue: WON_LINE_ITEM_DEAL.actualValue }),
        );
    });

    it("preserves realized value when a won deal is toggled through lost to open before submit", async () => {
        const form = createOutcomeDraftHarness();

        form.selectOutcome("Lost");
        expect(form.getDraft().actualValue).toBe(WON_LINE_ITEM_DEAL.actualValue);
        form.selectOutcome("Open");
        await submitDealDraftUpdates([{ dealId: WON_LINE_ITEM_DEAL.id, draft: form.getDraft() }]);

        expect(updateDealMock).toHaveBeenCalledWith(
            WON_LINE_ITEM_DEAL.id,
            expect.objectContaining({ actualValue: WON_LINE_ITEM_DEAL.actualValue }),
        );
    });

    it("submits zero for a committed loss while retaining the provisional draft value", async () => {
        const form = createOutcomeDraftHarness();

        form.selectOutcome("Lost");
        await submitDealDraftUpdate(WON_LINE_ITEM_DEAL.id, form.getDraft());
        await submitDealDraftUpdates([{ dealId: WON_LINE_ITEM_DEAL.id, draft: form.getDraft() }]);

        expect(form.getDraft().actualValue).toBe(WON_LINE_ITEM_DEAL.actualValue);
        expect(updateDealMock).toHaveBeenNthCalledWith(
            1,
            WON_LINE_ITEM_DEAL.id,
            expect.objectContaining({ actualValue: 0 }),
        );
        expect(updateDealMock).toHaveBeenNthCalledWith(
            2,
            WON_LINE_ITEM_DEAL.id,
            expect.objectContaining({ actualValue: 0 }),
        );
    });

    it("preserves realized value while a failure-stage selection remains provisional", () => {
        const form = createOutcomeDraftHarness();

        form.selectFailureStage();

        expect(form.getDraft().won).toBe(false);
        expect(form.getDraft().actualValue).toBe(WON_LINE_ITEM_DEAL.actualValue);
    });
});
