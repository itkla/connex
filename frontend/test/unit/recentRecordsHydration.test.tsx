/** @vitest-environment jsdom */

import { Suspense, useLayoutEffect } from "react";
import { act, cleanup, render } from "@testing-library/react";
import { renderToString } from "react-dom/server";
import { hydrateRoot } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { RecentRecordsProvider, useRecentRecords } from "@/app/hooks/useRecentRecords";
import RecentRecordBridge from "@/app/components/actions/RecentRecordBridge";
import { recentRecordsStorageKey, serializeRecents } from "@/app/lib/recentRecords";

const context = vi.hoisted(() => ({ userId: 7, workspaceId: 8, canPersist: () => true }));
vi.mock("@/app/hooks/useActions", () => ({ useActions: () => ({ context: { user: { id: context.userId } } }) }));
vi.mock("@/app/hooks/useWorkspace", () => ({ useWorkspace: () => ({ activeWorkspaceId: context.workspaceId }) }));
vi.mock("@/app/hooks/useAccountStorageGeneration", () => ({ useAccountStorageGeneration: () => context.canPersist }));

function storeRecord(userId: number, workspaceId: number, label: string) {
    localStorage.setItem(recentRecordsStorageKey(userId, workspaceId), serializeRecents([
        { t: "person", id: 10, label, ts: 1 },
    ]));
}

beforeEach(() => {
    context.userId = 7;
    context.workspaceId = 8;
    localStorage.clear();
});
afterEach(() => { cleanup(); localStorage.clear(); });

describe("recent records hydration", () => {
    it.each(["view", "stored", "stored and view"] as const)("hydrates a suspended consumer with %s history", async (source) => {
        const hasStored = source !== "view";
        const recordView = source !== "stored";
        if (hasStored) storeRecord(7, 8, "Stored contact");
        let pause = false;
        let release: (() => void) | undefined;
        const blocked = new Promise<void>((resolve) => { release = resolve; });
        function Recents() {
            const { recents } = useRecentRecords();
            if (pause) throw blocked;
            return <aside>{recents.length > 0 ? <ul>{recents.map((item) => <li key={item.id}>{item.label}</li>)}</ul> : null}</aside>;
        }
        const view = <RecentRecordsProvider>
            <Suspense fallback={null}><Recents /></Suspense>
            {recordView ? <RecentRecordBridge type="person" id={42} label="Viewed contact" /> : null}
        </RecentRecordsProvider>;
        const container = document.createElement("div");
        container.innerHTML = renderToString(view);
        document.body.append(container);
        const errors: unknown[] = [];
        pause = true;
        let root: ReturnType<typeof hydrateRoot> | undefined;
        try {
            await act(async () => {
                root = hydrateRoot(container, view, { onRecoverableError: (error) => errors.push(error) });
            });
            await act(async () => {
                pause = false;
                release?.();
                await blocked;
            });
            const expected = [...(recordView ? ["Viewed contact"] : []), ...(hasStored ? ["Stored contact"] : [])];
            expect([...container.querySelectorAll("li")].map((item) => item.textContent)).toEqual(expected);
            const stored = localStorage.getItem(recentRecordsStorageKey(7, 8));
            for (const label of expected) expect(stored).toContain(label);
        } finally {
            await act(async () => root?.unmount());
            container.remove();
        }
        expect(errors.map((error) => String(error))).toEqual([]);
    });

    it.each(["workspace", "account"] as const)("isolates a new %s and rejects its retained predecessor callback", (scope) => {
        storeRecord(7, 8, "Original scope contact");
        let probe: ReturnType<typeof useRecentRecords> | null = null;
        const observed: { userId: number; workspaceId: number; labels: string[] }[] = [];
        function Probe() {
            const value = useRecentRecords();
            useLayoutEffect(() => {
                probe = value;
                observed.push({ userId: context.userId, workspaceId: context.workspaceId, labels: value.recents.map((item) => item.label) });
            });
            return <aside>{value.recents.map((item) => <span key={item.id}>{item.label}</span>)}</aside>;
        }
        function current() {
            if (!probe) throw new Error("Recent records consumer is not mounted");
            return probe;
        }
        const view = render(<RecentRecordsProvider><Probe /></RecentRecordsProvider>);
        expect(current().recents.map((item) => item.label)).toEqual(["Original scope contact"]);
        const oldRecord = current().record;
        const oldKey = recentRecordsStorageKey(7, 8);
        if (scope === "workspace") context.workspaceId = 9;
        else context.userId = 9;
        const newKey = recentRecordsStorageKey(context.userId, context.workspaceId);
        storeRecord(context.userId, context.workspaceId, "New scope contact");
        view.rerender(<RecentRecordsProvider><Probe /></RecentRecordsProvider>);

        expect(current().recents.map((item) => item.label)).toEqual(["New scope contact"]);
        expect(observed.filter((value) => value.userId === context.userId && value.workspaceId === context.workspaceId)
            .every((value) => !value.labels.includes("Original scope contact"))).toBe(true);
        const oldStored = localStorage.getItem(oldKey);
        const newStored = localStorage.getItem(newKey);
        act(() => oldRecord({ type: "person", id: 11, label: "Stale callback contact" }));
        expect(localStorage.getItem(oldKey)).toBe(oldStored);
        expect(localStorage.getItem(newKey)).toBe(newStored);
        act(() => current().record({ type: "person", id: 12, label: "Current scope view" }));
        expect(current().recents.map((item) => item.label)).toEqual(["Current scope view", "New scope contact"]);
        expect(localStorage.getItem(newKey)).not.toContain("Original scope contact");
        expect(localStorage.getItem(newKey)).not.toContain("Stale callback contact");
        expect(localStorage.getItem(oldKey)).toBe(oldStored);
    });
});
