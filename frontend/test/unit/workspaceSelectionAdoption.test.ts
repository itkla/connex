import { readFileSync } from "node:fs";
import path from "node:path";
import { createElement } from "react";
import { renderToString } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";

import { WorkspaceProvider, useWorkspace } from "@/app/hooks/useWorkspace";
import {
    getActiveWorkspaceMembers,
    getWorkspaceMembers,
    leaveWorkspace,
} from "@/app/lib/api";

const router = vi.hoisted(() => ({
    refresh: vi.fn(),
    replace: vi.fn(),
}));

vi.mock("next/navigation", () => ({
    useRouter: () => router,
}));

const PROVIDER = "app/hooks/useWorkspace.tsx";
const MEMBERSHIP_PANEL = "app/components/account/MembershipPanel.tsx";

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), "utf8");
}

function section(contents: string, start: string, end: string): string {
    const startIndex = contents.indexOf(start);
    return contents.slice(startIndex, contents.indexOf(end, startIndex + start.length));
}

function deferred() {
    let resolve: () => void = () => {};
    const promise = new Promise<void>((resolvePromise) => {
        resolve = resolvePromise;
    });
    return { promise, resolve };
}

function renderWorkspaceProvider() {
    let workspace: ReturnType<typeof useWorkspace> | undefined;

    function CaptureWorkspace() {
        workspace = useWorkspace();
        return null;
    }

    const providerProps: Parameters<typeof WorkspaceProvider>[0] = {
        initialWorkspaces: [],
        initialActiveId: 7,
        children: createElement(CaptureWorkspace),
    };
    renderToString(createElement(WorkspaceProvider, providerProps));
    if (!workspace) throw new Error("Workspace provider did not render");
    return workspace;
}

afterEach(() => {
    vi.clearAllMocks();
    vi.unstubAllGlobals();
});

describe("authoritative workspace selection adoption", () => {
    it("publishes the same id to rendered state and the imperative ref", () => {
        const provider = source(PROVIDER);
        const publisher = section(
            provider,
            "const publishActiveWorkspace",
            "const runSelectionChange",
        );

        expect(provider).toContain("runSelectionChange: SelectionChangeRunner;");
        expect(publisher).toContain("activeWorkspaceIdRef.current = id;");
        expect(publisher).toContain("setActiveWorkspaceId(id);");
    });

    it("admits only one selection-changing operation at a time", async () => {
        const pendingSelection = deferred();
        const firstOperation = vi.fn(() => pendingSelection.promise);
        const blockedOperation = vi.fn(async () => {});
        const workspace = renderWorkspaceProvider();

        const inFlightSelection = workspace.runSelectionChange(firstOperation);
        await vi.waitFor(() => expect(firstOperation).toHaveBeenCalledOnce());

        await expect(workspace.runSelectionChange(blockedOperation)).rejects.toThrow(
            "A workspace operation is already in progress",
        );
        expect(blockedOperation).not.toHaveBeenCalled();

        pendingSelection.resolve();
        await expect(inFlightSelection).resolves.toBeUndefined();
        await expect(workspace.runSelectionChange(blockedOperation)).resolves.toBeUndefined();
        expect(blockedOperation).toHaveBeenCalledOnce();
    });

    it("routes switches, creation, acceptance, and leave through the same mutex", () => {
        const provider = source(PROVIDER);
        const switching = section(provider, "const runInWorkspace", "const switchTo");
        const creation = section(provider, "const create", "const activeWorkspace");
        const panel = source(MEMBERSHIP_PANEL);
        const acceptance = section(panel, "const accept =", "const decline =");
        const leaving = section(panel, "const doLeave =", "return (");

        expect(switching).toContain("runSelectionChange(async (publishActiveWorkspace) =>");
        expect(creation).toContain("runSelectionChange(async (publishActiveWorkspace) =>");
        expect(acceptance).toContain("runSelectionChange(async (publishActiveWorkspace) =>");
        expect(leaving).toContain("runSelectionChange(async (publishActiveWorkspace) =>");
        expect(switching).toContain("publishActiveWorkspace(id);");
        expect(creation).toContain("publishActiveWorkspace(workspace.id);");
        expect(switching).not.toContain("activeWorkspaceIdRef.current = id;");
        expect(creation).not.toContain("activeWorkspaceIdRef.current = workspace.id;");
    });

    it("publishes accepted and remaining workspace decisions before refresh or navigation", () => {
        const panel = source(MEMBERSHIP_PANEL);
        const acceptance = section(panel, "const accept =", "const decline =");
        const leaving = section(panel, "const doLeave =", "return (");

        expect(acceptance).toMatch(/const accepted = await acceptWorkspace\(workspace\.id\);[\s\S]*publishActiveWorkspace\(accepted\.id\);[\s\S]*router\.refresh\(\)/);
        expect(leaving).toMatch(/const selection = await leaveWorkspace\(activeWorkspaceId\);[\s\S]*publishActiveWorkspace\(selection\.activeWorkspaceId\);[\s\S]*router\.replace\(selection\.activeWorkspaceId !== null \? "\/dashboard" : "\/onboarding"\);[\s\S]*router\.refresh\(\)/);
    });

    it("disables accept and leave while another selection change is in progress", () => {
        const panel = source(MEMBERSHIP_PANEL);

        expect(panel).toContain("activeWorkspace, runSelectionChange, switching");
        expect(panel).toContain("disabled={busy || switching}");
        expect(panel).toContain("disabled={!activeWorkspaceId || switching}");
        expect(panel).toContain("disabled={leaving || switching}");
    });

    it("documents serialization without promising recovery from response-body failure", () => {
        const provider = source(PROVIDER);
        const contract = section(provider, "/**", "export function WorkspaceProvider");
        const normalizedContract = contract.replace(/^\s*\*\s?/gm, "").replace(/\s+/g, " ");

        expect(normalizedContract).toContain("one selection-changing operation at a time");
        expect(normalizedContract).toContain("cookie is applied before that result is published");
        expect(normalizedContract).toContain("body fails to read or parse");
        expect(normalizedContract).toContain("#1023");
        expect(normalizedContract).not.toContain("matching the order in which the browser");
        expect(provider).not.toContain("setActiveWorkspaceId(initialActiveId)");
    });

    it("uses the returned selection for matching default and explicit workspace headers", async () => {
        expect(
            source(MEMBERSHIP_PANEL).includes("publishActiveWorkspace(selection.activeWorkspaceId);"),
        ).toBe(true);
        const browserDocument = { cookie: "NEXT_LOCALE=en; connex_workspace=7" };
        const memberRequests: Array<{ path: string; workspaceId: string | null }> = [];
        vi.stubGlobal("document", browserDocument);
        vi.stubGlobal("fetch", vi.fn(async (
            input: string | URL | Request,
            init?: RequestInit,
        ): Promise<Response> => {
            const requestUrl = input instanceof Request ? input.url : input.toString();
            const url = new URL(requestUrl, "http://localhost:8080");
            if (url.pathname === "/api/workspaces/7/leave") {
                browserDocument.cookie = "NEXT_LOCALE=en; connex_workspace=22";
                return new Response('{"activeWorkspaceId":22}', {
                    status: 200,
                    headers: { "Content-Type": "application/json" },
                });
            }
            if (url.pathname === "/api/workspaces/22/members") {
                memberRequests.push({
                    path: url.pathname,
                    workspaceId: new Headers(init?.headers).get("X-Workspace-Id"),
                });
                return new Response("[]", {
                    status: 200,
                    headers: { "Content-Type": "application/json" },
                });
            }
            throw new Error(`Unexpected request to ${url.pathname}`);
        }));

        const selection = await leaveWorkspace(7);
        if (selection.activeWorkspaceId === null) {
            throw new Error("Expected the leave response to select a remaining workspace");
        }
        await getActiveWorkspaceMembers();
        await getWorkspaceMembers(selection.activeWorkspaceId, {
            headers: { "X-Workspace-Id": String(selection.activeWorkspaceId) },
        });

        expect(memberRequests).toEqual([
            { path: "/api/workspaces/22/members", workspaceId: "22" },
            { path: "/api/workspaces/22/members", workspaceId: "22" },
        ]);
    });
});
