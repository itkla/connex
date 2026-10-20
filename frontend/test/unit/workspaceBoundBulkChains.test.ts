/** @vitest-environment jsdom */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
    bulkAddTagToCompanies,
    bulkAddTagToContacts,
    bulkAddTagToDeals,
    bulkArchiveCompanies,
    bulkArchiveContacts,
    bulkAssignCompanyOwner,
    bulkAssignDealOwner,
    bulkAssignPersonOwner,
    bulkChangeDealStage,
    bulkDeleteDeals,
    bulkRemoveTagFromCompanies,
    bulkRemoveTagFromContacts,
    bulkRemoveTagFromDeals,
    bulkRestoreCompanies,
    bulkRestoreContacts,
} from "@/app/lib/api";
import { bindRenderedWorkspace, releaseRenderedWorkspace } from "@/app/lib/renderedWorkspace";
import { json, ORIGIN, shell, stubBackend, switchWorkspace } from "./helpers/workspaceChainBackend";

const ids = Array.from({ length: 1001 }, (_, index) => index + 1);
beforeEach(() => {
    bindRenderedWorkspace(shell, ORIGIN);
    document.cookie = "connex_workspace=" + ORIGIN + "; path=/";
});
afterEach(() => {
    releaseRenderedWorkspace(shell);
    document.cookie = "connex_workspace=; path=/; max-age=0";
    vi.unstubAllGlobals();
});

describe("chunked API writes capture their origin before the first chunk", () => {
    it.each([
        { path: "/api/persons/bulk/tags/add", run: (init: RequestInit) => bulkAddTagToContacts(ids, 7, init) },
        { path: "/api/persons/bulk/tags/remove", run: (init: RequestInit) => bulkRemoveTagFromContacts(ids, 7, init) },
        { path: "/api/persons/bulk/archive", run: (init: RequestInit) => bulkArchiveContacts(ids, init) },
        { path: "/api/persons/bulk/restore", run: (init: RequestInit) => bulkRestoreContacts(ids, init) },
        { path: "/api/companies/bulk/tags/add", run: (init: RequestInit) => bulkAddTagToCompanies(ids, 7, init) },
        { path: "/api/companies/bulk/tags/remove", run: (init: RequestInit) => bulkRemoveTagFromCompanies(ids, 7, init) },
        { path: "/api/companies/bulk/archive", run: (init: RequestInit) => bulkArchiveCompanies(ids, init) },
        { path: "/api/companies/bulk/restore", run: (init: RequestInit) => bulkRestoreCompanies(ids, init) },
        { path: "/api/companies/bulk/owner", run: (init: RequestInit) => bulkAssignCompanyOwner(ids, 7, init) },
        { path: "/api/persons/bulk/owner", run: (init: RequestInit) => bulkAssignPersonOwner(ids, 7, init) },
        { path: "/api/deals/bulk/tags/add", run: (init: RequestInit) => bulkAddTagToDeals(ids, 7, init) },
        { path: "/api/deals/bulk/tags/remove", run: (init: RequestInit) => bulkRemoveTagFromDeals(ids, 7, init) },
        { path: "/api/deals/bulk/delete", run: (init: RequestInit) => bulkDeleteDeals(ids, init) },
        { path: "/api/deals/bulk/owner", run: (init: RequestInit) => bulkAssignDealOwner(ids, 7, init) },
        { path: "/api/deals/bulk/stage", run: (init: RequestInit) => bulkChangeDealStage(ids, 7, init) },
    ])("keeps both $path chunks in A after an explicit switch to B", async ({ path, run }) => {
        let responses = 0;
        const backend = stubBackend(path, (requestPath) => requestPath === path ? json({ succeeded: responses++ === 0 ? 999 : 0, failed: 1, errors: [{ rowIndex: 0, reason: "test conflict" }] }) : undefined);
        const controller = new AbortController();
        const pending = run({ signal: controller.signal, headers: { "X-Chain-Test": "retained" } });
        await backend.started.promise;
        switchWorkspace();
        backend.held.resolve(backend.response(path));
        expect(await pending).toEqual({ succeeded: 999, failed: 2, errors: [{ rowIndex: 0, reason: "test conflict" }, { rowIndex: 1000, reason: "test conflict" }] });
        expect(backend.requests.map(({ path, workspace }) => ({ path, workspace }))).toEqual([
            { path, workspace: String(ORIGIN) }, { path, workspace: String(ORIGIN) },
        ]);
        expect(backend.requests.every(({ init }) => init.signal === controller.signal && new Headers(init.headers).get("X-Chain-Test") === "retained")).toBe(true);
        backend.requests.forEach(({ init }, index) => {
            const body: unknown = JSON.parse(String(init.body));
            expect(body).toEqual(expect.objectContaining({ ids: ids.slice(index * 1000, (index + 1) * 1000) }));
        });
    });
});
