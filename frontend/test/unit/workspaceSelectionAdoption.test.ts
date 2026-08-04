import { readFileSync } from "node:fs";
import path from "node:path";
import { act, createElement } from "react";
import { renderToString } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";

import { WorkspaceProvider, useWorkspace } from "@/app/hooks/useWorkspace";
import type { Workspace } from "@/app/lib/types";
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

function installMinimalDocument(): HTMLElement {
    class HtmlIFrameElement {}

    const documentTarget = {
        nodeType: 9,
        activeElement: null,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        createElement: vi.fn(() => containerTarget),
    };
    const windowTarget = {
        document: documentTarget,
        event: undefined,
        HTMLIFrameElement: HtmlIFrameElement,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
    };
    const containerTarget = {
        nodeType: 1,
        tagName: "DIV",
        nodeName: "DIV",
        namespaceURI: "http://www.w3.org/1999/xhtml",
        ownerDocument: documentTarget,
        firstChild: null,
        lastChild: null,
        parentNode: null,
        textContent: "",
        style: {},
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        appendChild: vi.fn(),
        insertBefore: vi.fn(),
        removeChild: vi.fn(),
        setAttribute: vi.fn(),
        removeAttribute: vi.fn(),
    };
    Object.assign(documentTarget, {
        defaultView: windowTarget,
        documentElement: containerTarget,
        body: containerTarget,
    });
    vi.stubGlobal("window", windowTarget);
    vi.stubGlobal("document", documentTarget);
    vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true);
    return document.createElement("div");
}

function workspaceFixture(id: number, name: string): Workspace {
    return {
        id,
        name,
        slug: name.toLowerCase().replaceAll(" ", "-"),
        timezone: null,
        role: "member",
        orgId: 1,
        orgName: "Acme",
        orgRole: null,
    };
}

async function renderInteractiveWorkspaceProvider(
    initialWorkspaces: Workspace[],
    initialActiveId: number | null,
) {
    const container = installMinimalDocument();
    const { createRoot } = await import("react-dom/client");
    const root = createRoot(container);
    let workspace: ReturnType<typeof useWorkspace> | undefined;

    function CaptureWorkspace() {
        workspace = useWorkspace();
        return null;
    }

    function readWorkspace() {
        if (!workspace) throw new Error("Workspace provider did not render");
        return workspace;
    }

    const providerProps: Parameters<typeof WorkspaceProvider>[0] = {
        initialWorkspaces,
        initialActiveId,
        children: createElement(CaptureWorkspace),
    };
    await act(async () => {
        root.render(createElement(WorkspaceProvider, providerProps));
    });
    return {
        readWorkspace,
        unmount: async () => act(async () => root.unmount()),
    };
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

    it("keeps the returned membership active immediately after acceptance without a refresh", async () => {
        const existing = workspaceFixture(7, "Existing workspace");
        const accepted = workspaceFixture(22, "Accepted workspace");
        const rendered = await renderInteractiveWorkspaceProvider([existing], existing.id);

        await act(async () => {
            await rendered.readWorkspace().runSelectionChange(async (
                publishActiveWorkspace,
                publishWorkspace,
            ) => {
                publishWorkspace(accepted);
                publishActiveWorkspace(accepted.id);
            });
        });

        expect(rendered.readWorkspace().activeWorkspace).not.toBeNull();
        expect(rendered.readWorkspace().activeWorkspace?.name).toBe("Accepted workspace");
        expect(rendered.readWorkspace().workspaces.filter(({ id }) => id === accepted.id)).toHaveLength(1);
        expect(router.refresh).not.toHaveBeenCalled();

        await rendered.unmount();
    });

    it("replaces an existing membership by id instead of duplicating it", async () => {
        const pending = workspaceFixture(22, "Pending workspace");
        const accepted = workspaceFixture(22, "Accepted workspace");
        const rendered = await renderInteractiveWorkspaceProvider([pending], null);

        await act(async () => {
            await rendered.readWorkspace().runSelectionChange(async (
                publishActiveWorkspace,
                publishWorkspace,
            ) => {
                publishWorkspace(accepted);
                publishActiveWorkspace(accepted.id);
            });
        });

        expect(rendered.readWorkspace().activeWorkspace?.name).toBe("Accepted workspace");
        expect(rendered.readWorkspace().workspaces.filter(({ id }) => id === accepted.id)).toEqual([
            accepted,
        ]);

        await rendered.unmount();
    });

    it("publishes workspace and organization identity without replacing membership roles", async () => {
        const active = {
            ...workspaceFixture(7, "Before"),
            role: "admin" as const,
            orgRole: "owner" as const,
        };
        const sibling = { ...workspaceFixture(8, "Sibling"), orgRole: "owner" as const };
        const rendered = await renderInteractiveWorkspaceProvider([active, sibling], active.id);

        await act(async () => {
            rendered.readWorkspace().publishWorkspaceIdentity({
                id: active.id,
                name: "After",
                slug: active.slug,
                timezone: "Asia/Tokyo",
            });
            rendered.readWorkspace().publishOrganizationIdentity({ id: active.orgId, name: "New organization" });
        });

        expect(rendered.readWorkspace().activeWorkspace).toMatchObject({
            name: "After",
            timezone: "Asia/Tokyo",
            role: "admin",
            orgName: "New organization",
            orgRole: "owner",
        });
        expect(rendered.readWorkspace().workspaces.find(({ id }) => id === sibling.id)?.orgName)
            .toBe("New organization");

        await rendered.unmount();
    });

    it("routes switches, creation, acceptance, and leave through the same mutex", () => {
        const provider = source(PROVIDER);
        const switching = section(provider, "const runInWorkspace", "const switchTo");
        const creation = section(provider, "const create", "const activeWorkspace");
        const panel = source(MEMBERSHIP_PANEL);
        const acceptance = section(panel, "const accept =", "const decline =");
        const leaving = section(panel, "const doLeave =", "return (");

        expect(switching).toContain("runSelectionChange(async (publishActiveWorkspace) =>");
        expect(creation).toContain("runSelectionChange(async (publishActiveWorkspace, publishWorkspace) =>");
        expect(acceptance).toContain("runSelectionChange(async (publishActiveWorkspace, publishWorkspace) =>");
        expect(leaving).toContain("runSelectionChange(async (publishActiveWorkspace) =>");
        expect(switching).toContain("publishActiveWorkspace(id);");
        expect(creation).toContain("publishWorkspace(workspace);");
        expect(creation).toContain("publishActiveWorkspace(workspace.id);");
        expect(switching).not.toContain("activeWorkspaceIdRef.current = id;");
        expect(creation).not.toContain("activeWorkspaceIdRef.current = workspace.id;");
    });

    it("publishes accepted and remaining workspace decisions before refresh or navigation", () => {
        const panel = source(MEMBERSHIP_PANEL);
        const acceptance = section(panel, "const accept =", "const decline =");
        const leaving = section(panel, "const doLeave =", "return (");

        expect(acceptance).toMatch(/const accepted = await acceptWorkspace\(workspace\.id\);[\s\S]*publishWorkspace\(accepted\);[\s\S]*publishActiveWorkspace\(accepted\.id\);[\s\S]*router\.refresh\(\)/);
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
