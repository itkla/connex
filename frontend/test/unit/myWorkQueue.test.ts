import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

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

describe("My Work queue on /me", () => {
    it("localizes every backend reason code in both catalogs", () => {
        for (const code of REASON_CODES) {
            expect(EN.MePage[`queueReason_${code}`], `en queueReason_${code}`).toBeTruthy();
            expect(JA.MePage[`queueReason_${code}`], `ja queueReason_${code}`).toBeTruthy();
        }
    });

    it("localizes every evidence code in both catalogs", () => {
        for (const code of EVIDENCE_CODES) {
            expect(EN.MePage[`queueEvidence_${code}`], `en queueEvidence_${code}`).toBeTruthy();
            expect(JA.MePage[`queueEvidence_${code}`], `ja queueEvidence_${code}`).toBeTruthy();
        }
    });

    it("discriminates every reason code in the component switch", () => {
        for (const code of REASON_CODES) {
            expect(QUEUE_SOURCE.includes(`"${code}"`), `switch case ${code}`).toBe(true);
        }
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

    it("treats stale versions as refresh, not failure", () => {
        expect(QUEUE_SOURCE).toContain("error.status === 409");
        expect(QUEUE_SOURCE).toContain('toastWarn(t("queueStale"))');
    });

    it("never renders a failed projection as an empty queue", () => {
        const emptyIndex = QUEUE_SOURCE.indexOf('t("queueEmptyTitle")');
        const unavailableGuard = QUEUE_SOURCE.indexOf('page.availability === "unavailable"');
        expect(unavailableGuard).toBeGreaterThan(-1);
        expect(unavailableGuard).toBeLessThan(emptyIndex);
    });

    it("declares truncated totals as at-least counts", () => {
        expect(QUEUE_SOURCE).toContain("page.totalsComplete");
        expect(QUEUE_SOURCE).toContain('t("queueAtLeast"');
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
