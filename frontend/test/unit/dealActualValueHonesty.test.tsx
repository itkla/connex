import {
    createElement,
    type ComponentProps,
    type PropsWithChildren,
    type ReactNode,
} from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import QuickEditDealSheet, { type DealDraft } from "@/app/components/records/deals/QuickEditDealSheet";
import { actualValueForOutcome } from "@/app/components/records/deals/dealOutcome";
import type { Deal } from "@/app/lib/types";

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
});
