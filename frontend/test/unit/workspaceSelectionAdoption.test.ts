import { readFileSync } from "node:fs";
import path from "node:path";
import { createElement } from "react";
import { renderToString } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";

import { WorkspaceProvider, useWorkspace } from "@/app/hooks/useWorkspace";
import {
    createWorkspace,
    getActiveWorkspaceMembers,
    getWorkspaceMembers,
    leaveWorkspace,
    switchWorkspace,
} from "@/app/lib/api";

const router = vi.hoisted(() => ({
    refresh: vi.fn(),
    replace: vi.fn(),
}));

vi.mock("next/navigation", () => ({
    useRouter: () => router,
}));

vi.mock("@/app/lib/api", async (importOriginal) => {
    const actual = await importOriginal<typeof import("@/app/lib/api")>();
    return {
        ...actual,
        createWorkspace: vi.fn(),
        switchWorkspace: vi.fn(),
    };
});

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
    let reject: (reason: Error) => void = () => {};
    const promise = new Promise<void>((resolvePromise, rejectPromise) => {
        resolve = resolvePromise;
        reject = rejectPromise;
    });
    return { promise, reject, resolve };
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

async function expectActiveWorkspace(
    workspace: ReturnType<typeof useWorkspace>,
    id: number,
) {
    vi.mocked(switchWorkspace).mockClear();
    const operation = vi.fn(async () => {});
    await expect(workspace.runInWorkspace(id, operation)).resolves.toBe(true);
    expect(switchWorkspace).not.toHaveBeenCalled();
    expect(operation).toHaveBeenCalledWith(false);
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
            "const runInWorkspace",
        );

        expect(provider).toContain("adoptActiveWorkspace: (id: number | null) => void;");
        expect(publisher).toContain("activeWorkspaceIdRef.current = id;");
        expect(publisher).toContain("setActiveWorkspaceId(id);");
    });

    it("keeps an adopted decision when an older in-flight switch fails", async () => {
        const pendingSwitch = deferred();
        vi.mocked(switchWorkspace).mockReturnValueOnce(pendingSwitch.promise);
        const workspace = renderWorkspaceProvider();

        const inFlightSwitch = workspace.runInWorkspace(9, async () => {});
        workspace.adoptActiveWorkspace(22);
        pendingSwitch.reject(new Error("switch failed"));
        await expect(inFlightSwitch).rejects.toThrow("switch failed");

        await expectActiveWorkspace(workspace, 22);
    });

    it("lets a later in-flight switch decision replace an adopted decision", async () => {
        const pendingSwitch = deferred();
        vi.mocked(switchWorkspace).mockReturnValueOnce(pendingSwitch.promise);
        const workspace = renderWorkspaceProvider();

        const inFlightSwitch = workspace.runInWorkspace(9, async () => {});
        workspace.adoptActiveWorkspace(22);
        pendingSwitch.resolve();
        await expect(inFlightSwitch).resolves.toBe(true);

        await expectActiveWorkspace(workspace, 9);
    });

    it.each(["resolve", "reject"] as const)(
        "keeps a later adoption when the switch callback will %s",
        async (settlement) => {
            vi.mocked(switchWorkspace).mockResolvedValueOnce(undefined);
            const pendingOperation = deferred();
            const operation = vi.fn(() => pendingOperation.promise);
            const workspace = renderWorkspaceProvider();

            const inFlightSwitch = workspace.runInWorkspace(9, operation);
            await vi.waitFor(() => expect(operation).toHaveBeenCalledWith(true));
            workspace.adoptActiveWorkspace(22);
            if (settlement === "resolve") {
                pendingOperation.resolve();
                await expect(inFlightSwitch).resolves.toBe(true);
            } else {
                pendingOperation.reject(new Error("operation failed"));
                await expect(inFlightSwitch).rejects.toThrow("operation failed");
            }

            await expectActiveWorkspace(workspace, 22);
        },
    );

    it("keeps an adopted decision when an older in-flight creation fails", async () => {
        const pendingCreation = deferred();
        vi.mocked(createWorkspace).mockReturnValueOnce(pendingCreation.promise.then(() => {
            throw new Error("creation unexpectedly resolved");
        }));
        const workspace = renderWorkspaceProvider();

        const inFlightCreation = workspace.create("New workspace");
        workspace.adoptActiveWorkspace(22);
        pendingCreation.reject(new Error("creation failed"));
        await expect(inFlightCreation).rejects.toThrow("creation failed");

        await expectActiveWorkspace(workspace, 22);
    });

    it("lets a later in-flight creation decision replace an adopted decision", async () => {
        const pendingCreation = deferred();
        vi.mocked(createWorkspace).mockReturnValueOnce(pendingCreation.promise.then(() => ({
            id: 33,
            name: "New workspace",
            slug: "new-workspace",
            role: "owner",
            orgId: 4,
            orgName: "Organization",
            orgRole: "owner",
        })));
        const workspace = renderWorkspaceProvider();

        const inFlightCreation = workspace.create("New workspace");
        workspace.adoptActiveWorkspace(22);
        pendingCreation.resolve();
        await expect(inFlightCreation).resolves.toMatchObject({ id: 33 });

        await expectActiveWorkspace(workspace, 33);
    });

    it("routes switches and creation through the same publisher", () => {
        const provider = source(PROVIDER);
        const switching = section(provider, "const runInWorkspace", "const switchTo");
        const creation = section(provider, "const create", "const activeWorkspace");

        expect(switching).toContain("publishActiveWorkspace(id);");
        expect(creation).toContain("publishActiveWorkspace(workspace.id);");
        expect(switching).not.toContain("activeWorkspaceIdRef.current = id;");
        expect(creation).not.toContain("activeWorkspaceIdRef.current = workspace.id;");
    });

    it("adopts accepted and remaining workspace decisions before refresh or navigation", () => {
        const panel = source(MEMBERSHIP_PANEL);
        const acceptance = section(panel, "const accept =", "const decline =");
        const leaving = section(panel, "const doLeave =", "return (");

        expect(acceptance).toMatch(/const accepted = await acceptWorkspace\(workspace\.id\);[\s\S]*adoptActiveWorkspace\(accepted\.id\);[\s\S]*router\.refresh\(\)/);
        expect(leaving).toMatch(/const selection = await leaveWorkspace\(activeWorkspaceId\);[\s\S]*adoptActiveWorkspace\(selection\.activeWorkspaceId\);[\s\S]*router\.replace\(selection\.activeWorkspaceId !== null \? "\/dashboard" : "\/onboarding"\);[\s\S]*router\.refresh\(\)/);
    });

    it("uses the returned selection for matching default and explicit workspace headers", async () => {
        expect(
            source(MEMBERSHIP_PANEL).includes("adoptActiveWorkspace(selection.activeWorkspaceId);"),
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
