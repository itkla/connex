import { readFileSync } from "node:fs";
import { join } from "node:path";
import { createElement, type ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import MyWorkQueue from "@/app/components/me/MyWorkQueue";
import type { CookieResult } from "@/app/lib/api";
import type {
    WorkItem,
    WorkItemPage,
    WorkItemSourceStatus,
} from "@/app/lib/types";

vi.mock("next/link", async () => {
    const React = await import("react");
    return {
        default: ({ href, children }: { href: string; children: ReactNode }) =>
            React.createElement("a", { href }, children),
    };
});

vi.mock("@/components/ui/tooltip", async () => {
    const React = await import("react");
    return {
        Tooltip: ({ children }: { children: ReactNode }) => React.createElement(React.Fragment, null, children),
        TooltipTrigger: ({ children }: { children: ReactNode }) => React.createElement(React.Fragment, null, children),
        TooltipContent: () => null,
        TooltipProvider: ({ children }: { children: ReactNode }) => React.createElement(React.Fragment, null, children),
    };
});

vi.mock("next/navigation", () => ({
    useRouter: () => ({ refresh: () => undefined }),
}));

vi.mock("next-intl", () => ({
    useTranslations: () => {
        const translate = (key: string, values?: Record<string, unknown>) =>
            values ? `${key}:${JSON.stringify(values)}` : key;
        return translate;
    },
    useFormatter: () => ({
        number: (value: number) => `#${value}`,
        dateTime: () => "date",
    }),
}));

vi.mock("@/app/lib/api", async () => {
    const actual = await vi.importActual<typeof import("@/app/lib/api")>("@/app/lib/api");
    return {
        ...actual,
        getMyWork: vi.fn(),
        completeMyWorkTask: vi.fn(),
        snoozeMyWorkNotification: vi.fn(),
        dismissMyWorkNotification: vi.fn(),
        decideMyWorkApproval: vi.fn(),
    };
});

const QUEUE_SOURCE = readFileSync(
    join(process.cwd(), "app/components/me/MyWorkQueue.tsx"),
    "utf8",
);
const PAGE_SOURCE = readFileSync(
    join(process.cwd(), "app/(app)/me/page.tsx"),
    "utf8",
);
const EN = JSON.parse(
    readFileSync(join(process.cwd(), "messages/en/me.json"), "utf8"),
) as { MePage: Record<string, string> };
const JA = JSON.parse(
    readFileSync(join(process.cwd(), "messages/ja/me.json"), "utf8"),
) as { MePage: Record<string, string> };

const REASON_CODES = [
    "task_overdue",
    "task_due_today",
    "task_due_soon",
    "task_open",
    "deal_close_overdue",
    "deal_closing_soon",
    "document_approval_pending",
] as const;

const EVIDENCE_CODES = [
    "task_due",
    "task_open",
    "deal_close_date",
    "approval_requested",
] as const;

function item(overrides: Partial<WorkItem> = {}): WorkItem {
    return {
        id: "task:1",
        source: "task",
        sourceId: 1,
        title: "Send the quote",
        reason: { code: "task_overdue", days: 2 },
        dueDate: "2026-08-29",
        urgency: "critical",
        evidence: [{
            code: "task_due",
            sourceType: "task",
            sourceId: 1,
            occurredAt: "2026-08-29T00:00:00Z",
            date: "2026-08-29",
        }],
        freshnessAt: "2026-08-31T00:00:00Z",
        asOf: "2026-08-31T00:00:00Z",
        currentVersion: "a".repeat(64),
        etag: `"${"a".repeat(64)}"`,
        context: { type: "task", id: 1, label: "Send the quote", href: "/activity/tasks" },
        permittedActions: ["complete", "open_context"],
        ...overrides,
    };
}

function status(overrides: Partial<WorkItemSourceStatus>): WorkItemSourceStatus {
    return { source: "task", status: "available", matchingTotal: 0, overallTotal: 0, ...overrides };
}

function pageOf(overrides: Partial<WorkItemPage> = {}): WorkItemPage {
    return {
        items: [],
        page: 1,
        size: 25,
        knownMatchingTotal: 0,
        knownOverallTotal: 0,
        totalsComplete: true,
        hasNext: false,
        hasNextKnown: true,
        availability: "available",
        sourceStatuses: [
            status({ source: "task" }),
            status({ source: "notification" }),
            status({ source: "document_approval" }),
        ],
        asOf: "2026-08-31T00:00:00Z",
        ...overrides,
    };
}

function render(initial: CookieResult<WorkItemPage>): string {
    return renderToStaticMarkup(createElement(MyWorkQueue, { userId: 7, initial }));
}

const ok = (data: WorkItemPage): CookieResult<WorkItemPage> => ({ ok: true, data });
const failedResult: CookieResult<WorkItemPage> = { ok: false };

describe("My Work queue rendered states", () => {
    it("renders a failed initial projection as unavailable, never as empty", () => {
        const html = render(failedResult);
        expect(html).toContain("queueUnavailableTitle");
        expect(html).not.toContain("queueEmptyTitle");
    });

    it("renders partial-with-zero-rows as unavailable naming the failed source, never caught-up", () => {
        const html = render(ok(pageOf({
            availability: "partial",
            totalsComplete: false,
            sourceStatuses: [
                status({ source: "task" }),
                status({ source: "notification", status: "unavailable", matchingTotal: null, overallTotal: null, errorCode: "provider_unavailable" }),
                status({ source: "document_approval" }),
            ],
        })));
        expect(html).toContain("queuePartialEmptyTitle");
        expect(html).toContain("queueSource_notification");
        expect(html).not.toContain("queueEmptyTitle");
    });

    it("renders partial-with-rows with the named-source banner and at-least counts", () => {
        const html = render(ok(pageOf({
            items: [item()],
            knownMatchingTotal: 1,
            knownOverallTotal: 1,
            availability: "partial",
            totalsComplete: false,
            sourceStatuses: [
                status({ source: "task", matchingTotal: 1, overallTotal: 1 }),
                status({ source: "notification", status: "unavailable", matchingTotal: null, overallTotal: null }),
                status({ source: "document_approval" }),
            ],
        })));
        expect(html).toContain("queuePartialNamed");
        expect(html).toContain("queueAtLeast");
        expect(html).not.toContain("#1</span>");
    });

    it("renders exact counts only for a fully available, totals-complete projection", () => {
        const html = render(ok(pageOf({
            items: [item()],
            knownMatchingTotal: 1,
            knownOverallTotal: 1,
        })));
        expect(html).toContain("#1");
        expect(html).not.toContain("queueAtLeast");
    });

    it("renders the genuine empty state with a Tasks action only when every source is available", () => {
        const html = render(ok(pageOf()));
        expect(html).toContain("queueEmptyTitle");
        expect(html).toContain("queueEmptyAction");
        expect(html).toContain("/activity/tasks");
    });

    it("shows pagination once a further page is reachable", () => {
        const html = render(ok(pageOf({
            items: [item()],
            knownMatchingTotal: 30,
            knownOverallTotal: 30,
            hasNext: true,
        })));
        expect(html).toContain("queuePage");
        expect(html).toContain("queueNext");
    });

    it("offers source and urgency filters on the section header", () => {
        const html = render(ok(pageOf({ items: [item()] })));
        expect(html).toContain("queueFilterSource");
        expect(html).toContain("queueFilterUrgency");
    });

    it("labels every row with its source and urgency for assistive tech", () => {
        const html = render(ok(pageOf({ items: [item({ urgency: "low" })], knownMatchingTotal: 1, knownOverallTotal: 1 })));
        expect(html).toContain("queueSource_task");
        expect(html).toContain("queueUrgency_low");
    });
});

describe("My Work queue contracts pinned at source level", () => {
    it("localizes every backend reason and evidence code in both catalogs", () => {
        for (const code of REASON_CODES) {
            expect(EN.MePage[`queueReason_${code}`], `en queueReason_${code}`).toBeTruthy();
            expect(JA.MePage[`queueReason_${code}`], `ja queueReason_${code}`).toBeTruthy();
        }
        for (const code of EVIDENCE_CODES) {
            expect(EN.MePage[`queueEvidence_${code}`], `en queueEvidence_${code}`).toBeTruthy();
            expect(JA.MePage[`queueEvidence_${code}`], `ja queueEvidence_${code}`).toBeTruthy();
        }
    });

    it("labels all four urgencies in both catalogs without calling critical overdue", () => {
        for (const urgency of ["critical", "high", "normal", "low"]) {
            expect(EN.MePage[`queueUrgency_${urgency}`], `en ${urgency}`).toBeTruthy();
            expect(JA.MePage[`queueUrgency_${urgency}`], `ja ${urgency}`).toBeTruthy();
        }
        expect(EN.MePage.queueUrgency_critical).not.toBe("Overdue");
    });

    it("guards every mutation with the item's strong version", () => {
        for (const call of [
            "completeMyWorkTask(item.sourceId, item.etag)",
            "dismissMyWorkNotification(item.sourceId, item.etag)",
            "snoozeMyWorkNotification(item.sourceId, item.etag",
            "decideMyWorkApproval(item.sourceId, item.etag",
        ]) {
            expect(QUEUE_SOURCE.includes(call), call).toBe(true);
        }
    });

    it("carries the approval step through decisions and never invents one", () => {
        expect(QUEUE_SOURCE).toContain("stepId: requireStepId(item)");
        expect(QUEUE_SOURCE).toContain("if (stepId == null) throw new ApiError");
    });

    it("treats 409 as refresh, 403 as permission, and other failures as unconfirmed with reconciliation", () => {
        expect(QUEUE_SOURCE).toContain("error.status === 409");
        expect(QUEUE_SOURCE).toContain('toastWarn(t("queueStale"))');
        expect(QUEUE_SOURCE).toContain("error.status === 403");
        expect(QUEUE_SOURCE).toContain("queueActionUnconfirmed");
        const catchBlock = QUEUE_SOURCE.slice(
            QUEUE_SOURCE.indexOf("catch (error)"),
            QUEUE_SOURCE.indexOf("finally {"),
        );
        expect(catchBlock).toContain("await reload()");
    });

    it("tracks pending actions per item and gates refetch generations", () => {
        expect(QUEUE_SOURCE).toContain("pendingIds.has(item.id)");
        expect(QUEUE_SOURCE).toContain("fetchGeneration.current");
        expect(QUEUE_SOURCE).toContain("if (generation !== fetchGeneration.current) return;");
    });

    it("decrements totals on optimistic removal", () => {
        expect(QUEUE_SOURCE).toContain("knownMatchingTotal: Math.max(0, current.knownMatchingTotal - 1)");
    });

    it("derives the open detail from current items so a refetch can close or update it", () => {
        expect(QUEUE_SOURCE).toContain("page?.items.find((row) => row.id === detailId)");
        expect(QUEUE_SOURCE).toContain("if (detailId != null && detail == null)");
    });

    it("offers snooze inside the focused detail surface", () => {
        const drawer = QUEUE_SOURCE.slice(QUEUE_SOURCE.indexOf("<Drawer"));
        expect(drawer).toContain('permittedActions.includes("snooze")');
        expect(drawer).toContain("SnoozeMenu");
    });

    it("guards rejection comments against silent discard and stays open until success", () => {
        expect(QUEUE_SOURCE).toContain("useUnsavedChangesGuard");
        expect(QUEUE_SOURCE).toContain("ConfirmDiscardDialog");
        expect(QUEUE_SOURCE).toContain("if (succeeded) setRejecting(null)");
    });

    it("routes approval context through the canonical documents anchor", () => {
        expect(QUEUE_SOURCE).toContain("dealDocumentsHref(item.context.id)");
    });

    it("replaces the read-only approval inbox on the page", () => {
        expect(PAGE_SOURCE).toContain("MyWorkQueue");
        expect(PAGE_SOURCE.includes("ApprovalInbox")).toBe(false);
    });

    it("emits the recipient state version after notification actions", () => {
        expect(QUEUE_SOURCE).toContain(
            "emitNotificationStateChanged(userId, response.notificationStateVersion)",
        );
    });
});
