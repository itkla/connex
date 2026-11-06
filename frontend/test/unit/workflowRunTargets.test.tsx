/** @vitest-environment jsdom */
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, describe, expect, it, vi } from "vitest";

import { runTargetKey, useWorkflowRunTargets } from "@/app/hooks/useWorkflowRunTargets";
import { getCompanyById, getGeneratedDocumentTargets } from "@/app/lib/api";
import type { Company, GeneratedDocumentTarget, WorkflowRunSummary } from "@/app/lib/types";

vi.mock("@/app/lib/api", () => ({
    getCompanyById: vi.fn(), getContactById: vi.fn(), getDealSummary: vi.fn(), getTaskById: vi.fn(),
    getGeneratedDocumentTargets: vi.fn(),
}));

globalThis.IS_REACT_ACT_ENVIRONMENT = true;
let root: Root | null = null;
afterEach(async () => {
    await act(async () => root?.unmount());
    root = null;
    document.body.replaceChildren();
    vi.resetAllMocks();
});

function company(id: number, name = `Company ${id}`): Company {
    return { id, name, website: "", industry: "", phone: "", address: "", logoUrl: "", createdAt: "2026-11-05", updatedAt: "2026-11-05" };
}

function run(id: number, recordType: "company" | "document" = "company"): WorkflowRunSummary {
    return {
        runKey: `legacy-${recordType}-${id}`, source: "legacy", status: "failed", legacyStatus: "failed", version: null,
        trigger: { type: "entity_change", event: recordType === "document" ? "document.finalized" : "company.updated", recordType, recordId: id },
        runtimeState: null, startedAt: "2026-11-05", finishedAt: null, durationMs: null, failure: null,
        stepDetailAvailable: false,
    };
}

function Probe({ runs, workspaceId }: { runs: WorkflowRunSummary[]; workspaceId: number }) {
    const { values, pending } = useWorkflowRunTargets(runs, workspaceId);
    return <div>{runs.map((item) => (
        <span key={item.runKey} data-target={runTargetKey(item)} data-pending={pending.has(runTargetKey(item))}
            data-href={values.get(runTargetKey(item))?.href}>
            {values.get(runTargetKey(item))?.name}
        </span>
    ))}</div>;
}

async function render(runs: WorkflowRunSummary[], workspaceId = 7) {
    if (root == null) {
        const container = document.createElement("div");
        document.body.appendChild(container);
        root = createRoot(container);
    }
    await act(async () => root?.render(<Probe runs={runs} workspaceId={workspaceId} />));
}

function deferredCompany() {
    let resolve: (value: Company) => void = () => { throw new Error("Promise not initialised"); };
    const promise = new Promise<Company>((complete) => { resolve = complete; });
    return { promise, resolve };
}

describe("workflow run target resolution", () => {
    it("resolves document titles and parent-deal links only for returned ids", async () => {
        vi.mocked(getGeneratedDocumentTargets).mockResolvedValue([
            { id: 61, dealId: 41, title: "Visible quote" },
            { id: 99, dealId: 42, title: "Unrequested quote" },
        ]);
        await render([run(61, "document"), run(62, "document"), run(63, "document")]);
        expect(document.querySelector('[data-target="document:61"]')?.textContent).toBe("Visible quote");
        expect(document.querySelector('[data-target="document:61"]')?.getAttribute("data-href")).toBe("/records/deals/41#deal-documents");
        for (const id of [62, 63]) {
            const target = document.querySelector(`[data-target="document:${id}"]`);
            expect(target?.textContent).toBe("");
            expect(target?.hasAttribute("data-href")).toBe(false);
            expect(target?.getAttribute("data-pending")).toBe("false");
        }
        expect(document.body.textContent).not.toContain("Unrequested quote");
        expect(getGeneratedDocumentTargets).toHaveBeenCalledWith([61, 62, 63], expect.objectContaining({
            headers: { "X-Workspace-Id": "7" }, signal: expect.any(AbortSignal),
        }));
    });

    it("batches 500 distinct documents across ten accumulated pages and caches absent targets", async () => {
        vi.mocked(getGeneratedDocumentTargets).mockImplementation(async (ids) => ids
            .filter((id) => id % 2 === 0)
            .map((id) => ({ id, dealId: 41, title: `Quote ${id}` })));
        const runs: WorkflowRunSummary[] = [];
        for (let page = 0; page < 10; page += 1) {
            runs.push(...Array.from({ length: 50 }, (_, index) => run(page * 50 + index + 1, "document")));
            await render([...runs]);
            expect(getGeneratedDocumentTargets).toHaveBeenCalledTimes(page + 1);
            expect(vi.mocked(getGeneratedDocumentTargets).mock.calls[page]?.[0]).toHaveLength(50);
        }
        await render([...runs, { ...run(1, "document"), runKey: "duplicate" }]);
        expect(getGeneratedDocumentTargets).toHaveBeenCalledTimes(10);
        expect(document.querySelector('[data-target="document:2"]')?.textContent).toBe("Quote 2");
    });

    it("shares the four-request ceiling across document batches and other record lookups", async () => {
        let active = 0;
        let peak = 0;
        async function lookup() {
            active += 1;
            peak = Math.max(peak, active);
            await Promise.resolve();
            active -= 1;
        }
        vi.mocked(getCompanyById).mockImplementation(async (id) => { await lookup(); return company(id); });
        vi.mocked(getGeneratedDocumentTargets).mockImplementation(async (ids) => {
            await lookup();
            return ids.map((id) => ({ id, dealId: 41, title: `Quote ${id}` }));
        });
        await render([
            ...Array.from({ length: 250 }, (_, index) => run(index + 1, "document")),
            ...Array.from({ length: 6 }, (_, index) => run(index + 1)),
        ]);
        expect(getGeneratedDocumentTargets).toHaveBeenCalledTimes(5);
        expect(getCompanyById).toHaveBeenCalledTimes(6);
        expect(peak).toBe(4);
        expect(document.querySelector('[data-target="document:250"]')?.textContent).toBe("Quote 250");
    });

    it("discards document batches and ignores stale titles after a workspace switch", async () => {
        let complete: (targets: GeneratedDocumentTarget[]) => void = () => { throw new Error("Promise not initialised"); };
        const stale = new Promise<GeneratedDocumentTarget[]>((resolve) => { complete = resolve; });
        vi.mocked(getGeneratedDocumentTargets).mockImplementation(async (_, init) =>
            new Headers(init?.headers).get("X-Workspace-Id") === "7" ? stale : []);
        const runs = Array.from({ length: 250 }, (_, index) => run(index + 1, "document"));
        await render(runs);
        expect(getGeneratedDocumentTargets).toHaveBeenCalledTimes(4);
        const signal = vi.mocked(getGeneratedDocumentTargets).mock.calls[0]?.[1]?.signal;
        await render([run(1, "document")], 8);
        expect(signal?.aborted).toBe(true);
        await act(async () => complete([{ id: 1, dealId: 41, title: "Stale private title" }]));
        expect(document.body.textContent).not.toContain("Stale private title");
        expect(getGeneratedDocumentTargets).toHaveBeenCalledTimes(5);
    });

    it("keeps failed batches and documents without usable titles or parents unlinked", async () => {
        vi.mocked(getGeneratedDocumentTargets).mockResolvedValueOnce([
            { id: 1, dealId: 41 }, { id: 2, dealId: 41, title: "  " }, { id: 3, dealId: -1, title: "Invalid parent" },
        ]).mockRejectedValueOnce(new Error("Private diagnostic"));
        await render([run(1, "document"), run(2, "document"), run(3, "document")]);
        await render([run(1, "document"), run(2, "document"), run(3, "document"), run(4, "document")]);
        await render([run(4, "document")]);
        expect(getGeneratedDocumentTargets).toHaveBeenCalledTimes(2);
        expect(document.body.textContent).toBe("");
        expect(document.querySelector('[data-target="document:4"]')?.getAttribute("data-pending")).toBe("false");
    });

    it("requests only 500 distinct targets across ten accumulated pages with at most four in flight", async () => {
        let active = 0;
        let peak = 0;
        vi.mocked(getCompanyById).mockImplementation(async (id) => {
            active += 1;
            peak = Math.max(peak, active);
            await Promise.resolve();
            active -= 1;
            return company(id);
        });
        const runs: WorkflowRunSummary[] = [];
        for (let page = 0; page < 10; page += 1) {
            runs.push(...Array.from({ length: 50 }, (_, index) => run(page * 50 + index + 1)));
            await render([...runs]);
            expect(getCompanyById).toHaveBeenCalledTimes((page + 1) * 50);
            expect(document.querySelector('[data-target="company:1"]')?.textContent).toBe("Company 1");
        }
        await render([...runs, { ...run(1), runKey: "legacy-501" }]);
        expect(getCompanyById).toHaveBeenCalledTimes(500);
        expect(peak).toBeLessThanOrEqual(4);
        expect(peak).toBeGreaterThan(1);
    });

    it("publishes settled targets immediately and does not refetch denied targets on later pages", async () => {
        const slow = deferredCompany();
        vi.mocked(getCompanyById).mockImplementation(async (id) => {
            if (id === 1) return slow.promise;
            if (id === 3) throw new Error("not visible");
            return company(id);
        });
        await render([run(1), run(2), run(3)]);
        expect(document.querySelector('[data-target="company:2"]')?.textContent).toBe("Company 2");
        expect(document.querySelector('[data-target="company:1"]')?.getAttribute("data-pending")).toBe("true");
        expect(document.querySelector('[data-target="company:3"]')?.getAttribute("data-pending")).toBe("false");
        await render([run(1), run(2), run(3), run(4)]);
        expect(getCompanyById).toHaveBeenCalledTimes(4);
        expect(document.querySelector('[data-target="company:2"]')?.textContent).toBe("Company 2");
        await act(async () => slow.resolve(company(1)));
        expect(document.querySelector('[data-target="company:1"]')?.textContent).toBe("Company 1");
    });

    it("discards workspace caches, aborts reads and ignores late responses when returning to a workspace", async () => {
        const other = deferredCompany();
        const returned = deferredCompany();
        let firstWorkspaceReads = 0;
        const signals: AbortSignal[] = [];
        vi.mocked(getCompanyById).mockImplementation(async (id, init) => {
            if (init?.signal) signals.push(init.signal);
            const workspace = new Headers(init?.headers).get("X-Workspace-Id");
            if (workspace === "8") return other.promise;
            firstWorkspaceReads += 1;
            return firstWorkspaceReads === 1 ? company(id, "Original workspace") : returned.promise;
        });
        await render([run(1)], 7);
        expect(document.body.textContent).toContain("Original workspace");
        await render([run(1)], 8);
        expect(signals[0]?.aborted).toBe(true);
        expect(document.body.textContent).not.toContain("Original workspace");
        await render([run(1)], 7);
        expect(signals[1]?.aborted).toBe(true);
        expect(document.body.textContent).not.toContain("Original workspace");
        await act(async () => other.resolve(company(1, "Other workspace")));
        expect(document.body.textContent).not.toContain("Other workspace");
        await act(async () => returned.resolve(company(1, "Checked again")));
        expect(document.body.textContent).toContain("Checked again");
        expect(getCompanyById).toHaveBeenCalledTimes(3);
    });

    it("aborts outstanding reads and stops queued requests when unmounted", async () => {
        const pending = deferredCompany();
        const signals: AbortSignal[] = [];
        vi.mocked(getCompanyById).mockImplementation(async (_, init) => {
            if (init?.signal) signals.push(init.signal);
            return pending.promise;
        });
        await render(Array.from({ length: 50 }, (_, index) => run(index + 1)));
        expect(getCompanyById).toHaveBeenCalledTimes(4);
        await act(async () => root?.unmount());
        root = null;
        expect(signals.every((signal) => signal.aborted)).toBe(true);
        await act(async () => pending.resolve(company(1)));
        expect(getCompanyById).toHaveBeenCalledTimes(4);
    });
});
