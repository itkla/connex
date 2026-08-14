import { readFileSync } from "node:fs";
import path from "node:path";
import { act, createElement } from "react";
import { renderToString } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";

import { WorkspaceProvider, useWorkspace } from "@/app/hooks/useWorkspace";
import type { Workspace } from "@/app/lib/types";
import {
    acceptInvite,
    acceptInviteLink,
    acceptWorkspace,
    createWorkspace,
    getActiveWorkspaceMembers,
    getWorkspaceMembers,
    leaveWorkspace,
    WorkspaceSelectionUnavailableError,
} from "@/app/lib/api";

const router = vi.hoisted(() => ({
    refresh: vi.fn(),
    replace: vi.fn(),
}));
const workspaceUnavailable = vi.hoisted<{
    onRetry: (() => Promise<void>) | undefined;
    renderCount: number;
}>(() => ({
    onRetry: undefined,
    renderCount: 0,
}));

vi.mock("next/navigation", () => ({
    useRouter: () => router,
}));
vi.mock("@/app/components/WorkspaceSelectionUnavailable", () => ({
    default: ({ onRetry }: { onRetry: () => Promise<void> }) => {
        workspaceUnavailable.onRetry = onRetry;
        workspaceUnavailable.renderCount += 1;
        return null;
    },
}));

const PROVIDER = "app/hooks/useWorkspace.tsx";
const MEMBERSHIP_PANEL = "app/components/account/MembershipPanel.tsx";
const API = "app/lib/api.ts";
const ONBOARDING_FORM = "app/onboarding/OnboardingForm.tsx";
const INVITE_ACCEPT = "app/components/invite/AcceptInvite.tsx";
const INVITE_LINK_ACCEPT = "app/components/invite/AcceptInviteLink.tsx";

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

function installMinimalDocument(cookie = ""): HTMLElement {
    class HtmlIFrameElement {}

    const documentTarget = {
        nodeType: 9,
        cookie,
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
        identityVersion: 0,
        role: "member",
        orgId: 1,
        orgName: "Acme",
        orgIdentityVersion: 0,
        orgRole: null,
    };
}

async function renderInteractiveWorkspaceProvider(
    initialWorkspaces: Workspace[],
    initialActiveId: number | null,
    cookie = "",
) {
    const container = installMinimalDocument(cookie);
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

    async function renderProvider(workspaces: Workspace[]) {
        const providerProps: Parameters<typeof WorkspaceProvider>[0] = {
            initialWorkspaces: workspaces,
            initialActiveId,
            children: createElement(CaptureWorkspace),
        };
        await act(async () => {
            root.render(createElement(WorkspaceProvider, providerProps));
        });
    }

    await renderProvider(initialWorkspaces);
    return {
        readWorkspace,
        rerender: renderProvider,
        unmount: async () => act(async () => root.unmount()),
    };
}

function unreadableResponse(message: string): Response {
    return new Response(new ReadableStream<Uint8Array>({
        start(controller) {
            controller.error(new Error(message));
        },
    }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
    });
}

function jsonResponse(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: { "Content-Type": "application/json" },
    });
}

afterEach(() => {
    vi.clearAllMocks();
    vi.unstubAllGlobals();
    workspaceUnavailable.onRetry = undefined;
    workspaceUnavailable.renderCount = 0;
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

    it("does not report a contended selection recovery as unavailable", async () => {
        const existing = workspaceFixture(7, "Existing workspace");
        const pendingSelection = deferred();
        const rendered = await renderInteractiveWorkspaceProvider([existing], existing.id);
        let selectionPromise = Promise.resolve();

        await act(() => {
            selectionPromise = rendered.readWorkspace().runSelectionChange(
                async () => pendingSelection.promise,
            );
        });
        await act(async () => {
            await rendered.readWorkspace().retrySelectionRecovery();
        });

        pendingSelection.resolve();
        await act(async () => {
            await selectionPromise;
        });

        expect(workspaceUnavailable.renderCount).toBe(0);
        expect(rendered.readWorkspace().activeWorkspaceId).toBe(existing.id);

        await rendered.unmount();
    });

    it("preserves a successful in-flight selection after a contended recovery retry", async () => {
        const existing = workspaceFixture(7, "Existing workspace");
        const selected = workspaceFixture(22, "Selected workspace");
        const pendingSelection = deferred();
        const rendered = await renderInteractiveWorkspaceProvider([existing], existing.id);
        let selectionPromise = Promise.resolve();

        await act(() => {
            selectionPromise = rendered.readWorkspace().runSelectionChange(async (
                publishActiveWorkspace,
                publishWorkspace,
            ) => {
                await pendingSelection.promise;
                publishWorkspace(selected);
                publishActiveWorkspace(selected.id);
            });
        });
        await act(async () => {
            await rendered.readWorkspace().retrySelectionRecovery();
        });

        pendingSelection.resolve();
        await act(async () => {
            await selectionPromise;
        });

        expect(workspaceUnavailable.renderCount).toBe(0);
        expect(rendered.readWorkspace().activeWorkspaceId).toBe(selected.id);
        expect(rendered.readWorkspace().activeWorkspace).toEqual(selected);
        expect(rendered.readWorkspace().switching).toBe(false);

        await rendered.unmount();
    });

    it("does not recover a selection request that failed before response headers", async () => {
        vi.stubGlobal("document", { cookie: "NEXT_LOCALE=en; connex_workspace=7" });
        const fetchMock = vi.fn().mockRejectedValue(new TypeError("Connection failed"));
        vi.stubGlobal("fetch", fetchMock);

        await expect(createWorkspace("Uncreated")).rejects.toThrow("Connection failed");

        expect(fetchMock).toHaveBeenCalledOnce();
    });

    it("recovers invalid create JSON from the cookie despite a different remembered active id", async () => {
        const existing = workspaceFixture(7, "Existing workspace");
        const created = workspaceFixture(22, "Created workspace");
        const rendered = await renderInteractiveWorkspaceProvider(
            [existing],
            existing.id,
            "NEXT_LOCALE=en; connex_workspace=7",
        );
        vi.stubGlobal("fetch", vi.fn(async (
            input: string | URL | Request,
            init?: RequestInit,
        ): Promise<Response> => {
            const url = new URL(input instanceof Request ? input.url : input.toString(), "http://localhost:8080");
            if (url.pathname === "/api/auth/csrf") return new Response("", { status: 503 });
            if (url.pathname === "/api/workspaces" && init?.method === "POST") {
                document.cookie = "NEXT_LOCALE=en; connex_workspace=22";
                return new Response('{"id":22', { status: 200 });
            }
            if (url.pathname === "/api/workspaces" && init?.method === "GET") {
                return jsonResponse({ workspaces: [existing, created], activeWorkspaceId: existing.id });
            }
            throw new Error(`Unexpected request to ${url.pathname}`);
        }));

        let result: Workspace | undefined;
        await act(async () => {
            result = await rendered.readWorkspace().create(created.name);
        });

        expect(result).toEqual(created);
        expect(rendered.readWorkspace().activeWorkspaceId).toBe(created.id);
        expect(rendered.readWorkspace().activeWorkspace).toEqual(created);
        expect(router.replace).toHaveBeenCalledWith("/dashboard");
        expect(router.refresh).toHaveBeenCalledOnce();

        await rendered.unmount();
    });

    it("recovers an unreadable pending-accept body before publishing membership and selection", async () => {
        const existing = workspaceFixture(7, "Existing workspace");
        const accepted = workspaceFixture(22, "Accepted workspace");
        const rendered = await renderInteractiveWorkspaceProvider(
            [existing],
            existing.id,
            "NEXT_LOCALE=en; connex_workspace=7",
        );
        vi.stubGlobal("fetch", vi.fn(async (
            input: string | URL | Request,
            init?: RequestInit,
        ): Promise<Response> => {
            const url = new URL(input instanceof Request ? input.url : input.toString(), "http://localhost:8080");
            if (url.pathname === "/api/auth/csrf") return new Response("", { status: 503 });
            if (url.pathname === "/api/workspaces/22/accept") {
                document.cookie = "NEXT_LOCALE=en; connex_workspace=22";
                return unreadableResponse("Accepted body stream failed");
            }
            if (url.pathname === "/api/workspaces" && init?.method === "GET") {
                return jsonResponse({ workspaces: [existing, accepted], activeWorkspaceId: accepted.id });
            }
            throw new Error(`Unexpected request to ${url.pathname}`);
        }));

        await act(async () => {
            await rendered.readWorkspace().runSelectionChange(async (
                publishActiveWorkspace,
                publishWorkspace,
            ) => {
                const recovered = await acceptWorkspace(accepted.id);
                publishWorkspace(recovered);
                publishActiveWorkspace(recovered.id);
            });
        });

        expect(rendered.readWorkspace().activeWorkspaceId).toBe(accepted.id);
        expect(rendered.readWorkspace().activeWorkspace).toEqual(accepted);

        await rendered.unmount();
    });

    it("recovers an unreadable empty switch body before publishing the requested selection", async () => {
        const existing = workspaceFixture(7, "Existing workspace");
        const selected = workspaceFixture(22, "Selected workspace");
        const rendered = await renderInteractiveWorkspaceProvider(
            [existing, selected],
            existing.id,
            "NEXT_LOCALE=en; connex_workspace=7",
        );
        vi.stubGlobal("fetch", vi.fn(async (
            input: string | URL | Request,
            init?: RequestInit,
        ): Promise<Response> => {
            const url = new URL(input instanceof Request ? input.url : input.toString(), "http://localhost:8080");
            if (url.pathname === "/api/auth/csrf") return new Response("", { status: 503 });
            if (url.pathname === "/api/workspaces/22/switch") {
                document.cookie = "NEXT_LOCALE=en; connex_workspace=22";
                return unreadableResponse("Empty body stream failed");
            }
            if (url.pathname === "/api/workspaces" && init?.method === "GET") {
                return jsonResponse({ workspaces: [existing, selected], activeWorkspaceId: selected.id });
            }
            throw new Error(`Unexpected request to ${url.pathname}`);
        }));

        await act(async () => {
            await expect(rendered.readWorkspace().runInWorkspace(selected.id, async () => {}))
                .resolves.toBe(true);
        });

        expect(rendered.readWorkspace().activeWorkspaceId).toBe(selected.id);

        await rendered.unmount();
    });

    it("recovers a bodyless last-workspace leave by publishing the cleared cookie selection", async () => {
        vi.stubGlobal("document", { cookie: "NEXT_LOCALE=en; connex_workspace=7" });
        vi.stubGlobal("fetch", vi.fn(async (
            input: string | URL | Request,
            init?: RequestInit,
        ): Promise<Response> => {
            const url = new URL(input instanceof Request ? input.url : input.toString(), "http://localhost:8080");
            if (url.pathname === "/api/workspaces/7/leave") {
                document.cookie = "NEXT_LOCALE=en";
                return new Response("", { status: 200 });
            }
            if (url.pathname === "/api/workspaces" && init?.method === "GET") {
                expect(new Headers(init.headers).get("X-Workspace-Id")).toBeNull();
                return jsonResponse({ workspaces: [], activeWorkspaceId: null });
            }
            throw new Error(`Unexpected request to ${url.pathname}`);
        }));

        await expect(leaveWorkspace(7)).resolves.toEqual({ activeWorkspaceId: null });
        expect(document.cookie).not.toContain("connex_workspace");
    });

    it.each([
        {
            label: "emailed invite",
            path: "/api/invites/accept",
            request: () => acceptInvite("a".repeat(64)),
        },
        {
            label: "shareable invite link",
            path: "/api/invite-links/accept",
            request: () => acceptInviteLink("b".repeat(64)),
        },
    ])("recovers an unreadable $label acceptance body", async ({ path: acceptancePath, request }) => {
        const accepted = workspaceFixture(22, "Accepted workspace");
        vi.stubGlobal("document", { cookie: "NEXT_LOCALE=en; connex_workspace=7" });
        vi.stubGlobal("fetch", vi.fn(async (
            input: string | URL | Request,
            init?: RequestInit,
        ): Promise<Response> => {
            const url = new URL(input instanceof Request ? input.url : input.toString(), "http://localhost:8080");
            if (url.pathname === acceptancePath) {
                document.cookie = "NEXT_LOCALE=en; connex_workspace=22";
                return unreadableResponse("Invite body stream failed");
            }
            if (url.pathname === "/api/workspaces" && init?.method === "GET") {
                return jsonResponse({ workspaces: [accepted], activeWorkspaceId: accepted.id });
            }
            throw new Error(`Unexpected request to ${url.pathname}`);
        }));

        await expect(request()).resolves.toEqual(accepted);
    });

    it("keeps the selection mutex held while the authoritative recovery read is pending", async () => {
        const accepted = workspaceFixture(22, "Accepted workspace");
        const workspace = renderWorkspaceProvider();
        const recoveryStarted = deferred();
        let resolveRecovery: (response: Response) => void = () => {};
        const recoveryResponse = new Promise<Response>((resolve) => {
            resolveRecovery = resolve;
        });
        vi.stubGlobal("document", { cookie: "NEXT_LOCALE=en; connex_workspace=7" });
        vi.stubGlobal("fetch", vi.fn(async (
            input: string | URL | Request,
            init?: RequestInit,
        ): Promise<Response> => {
            const url = new URL(input instanceof Request ? input.url : input.toString(), "http://localhost:8080");
            if (url.pathname === "/api/workspaces/22/accept") {
                document.cookie = "NEXT_LOCALE=en; connex_workspace=22";
                return unreadableResponse("Accepted body stream failed");
            }
            if (url.pathname === "/api/workspaces" && init?.method === "GET") {
                recoveryStarted.resolve();
                return recoveryResponse;
            }
            throw new Error(`Unexpected request to ${url.pathname}`);
        }));

        const firstSelection = workspace.runSelectionChange(async () => {
            await acceptWorkspace(accepted.id);
        });
        await recoveryStarted.promise;

        await expect(workspace.runSelectionChange(async () => {})).rejects.toThrow(
            "A workspace operation is already in progress",
        );

        resolveRecovery(jsonResponse({ workspaces: [accepted], activeWorkspaceId: accepted.id }));
        await expect(firstSelection).resolves.toBeUndefined();
    });

    it("fails closed and retries under the mutex when authoritative recovery is unavailable", async () => {
        const existing = workspaceFixture(7, "Existing workspace");
        const created = workspaceFixture(22, "Created workspace");
        const rendered = await renderInteractiveWorkspaceProvider(
            [existing],
            existing.id,
            "NEXT_LOCALE=en; connex_workspace=7",
        );
        const retryStarted = deferred();
        let resolveRetry: (response: Response) => void = () => {};
        const retryResponse = new Promise<Response>((resolve) => {
            resolveRetry = resolve;
        });
        let snapshotReads = 0;
        vi.stubGlobal("fetch", vi.fn(async (
            input: string | URL | Request,
            init?: RequestInit,
        ): Promise<Response> => {
            const url = new URL(input instanceof Request ? input.url : input.toString(), "http://localhost:8080");
            if (url.pathname === "/api/auth/csrf") return new Response("", { status: 503 });
            if (url.pathname === "/api/workspaces" && init?.method === "POST") {
                document.cookie = "NEXT_LOCALE=en; connex_workspace=22";
                return new Response('{"id":22', { status: 200 });
            }
            if (url.pathname === "/api/workspaces" && init?.method === "GET") {
                snapshotReads += 1;
                if (snapshotReads === 1) return new Response("", { status: 503 });
                retryStarted.resolve();
                return retryResponse;
            }
            throw new Error(`Unexpected request to ${url.pathname}`);
        }));

        await act(async () => {
            await expect(rendered.readWorkspace().create(created.name))
                .rejects.toBeInstanceOf(WorkspaceSelectionUnavailableError);
        });

        expect(workspaceUnavailable.renderCount).toBeGreaterThan(0);
        expect(workspaceUnavailable.onRetry).toBeDefined();
        const staleWorkspace = rendered.readWorkspace();
        const blockedWhileUnavailable = vi.fn(async () => {});
        await expect(staleWorkspace.runSelectionChange(blockedWhileUnavailable)).rejects.toThrow(
            "Workspace selection is unavailable",
        );
        expect(blockedWhileUnavailable).not.toHaveBeenCalled();

        const retry = workspaceUnavailable.onRetry;
        if (!retry) throw new Error("Unavailable state did not expose recovery");
        let retryPromise = Promise.resolve();
        await act(() => {
            retryPromise = retry();
        });
        await retryStarted.promise;

        await expect(staleWorkspace.runSelectionChange(async () => {})).rejects.toThrow(
            "A workspace operation is already in progress",
        );
        resolveRetry(jsonResponse({ workspaces: [created], activeWorkspaceId: created.id }));
        await act(async () => {
            await retryPromise;
        });

        expect(document.cookie).toContain("connex_workspace=22");
        expect(rendered.readWorkspace().activeWorkspaceId).toBe(created.id);
        expect(rendered.readWorkspace().activeWorkspace).toEqual(created);
        expect(router.refresh).toHaveBeenCalledOnce();
        const provider = source(PROVIDER);
        expect(provider).toContain("publishActiveWorkspace(null);");

        await rendered.unmount();
    });

    it("times out recovery, aborts its read, and ignores a late stale snapshot", async () => {
        vi.useFakeTimers();
        const existing = workspaceFixture(7, "Existing workspace");
        const stale = workspaceFixture(22, "Stale workspace");
        const newest = workspaceFixture(22, "Newest workspace");
        const rendered = await renderInteractiveWorkspaceProvider(
            [existing],
            existing.id,
            "NEXT_LOCALE=en; connex_workspace=22",
        );
        let resolveLateResponse: (response: Response) => void = () => {};
        const lateResponse = new Promise<Response>((resolve) => {
            resolveLateResponse = resolve;
        });
        const recoverySignals: AbortSignal[] = [];
        let snapshotReads = 0;
        vi.stubGlobal("fetch", vi.fn(async (
            input: string | URL | Request,
            init?: RequestInit,
        ): Promise<Response> => {
            const url = new URL(input instanceof Request ? input.url : input.toString(), "http://localhost:8080");
            if (url.pathname === "/api/workspaces" && init?.method === "GET") {
                snapshotReads += 1;
                if (snapshotReads === 1) {
                    if (init.signal) recoverySignals.push(init.signal);
                    return lateResponse;
                }
                return jsonResponse({ workspaces: [newest], activeWorkspaceId: newest.id });
            }
            throw new Error(`Unexpected request to ${url.pathname}`);
        }));

        let firstRetry = Promise.resolve();
        try {
            await act(() => {
                firstRetry = rendered.readWorkspace().retrySelectionRecovery();
            });
            let firstRetrySettled = false;
            void firstRetry.then(() => {
                firstRetrySettled = true;
            });

            await act(async () => {
                await vi.advanceTimersToNextTimerAsync();
            });

            expect(firstRetrySettled).toBe(true);
            const firstSignal = recoverySignals[0];
            if (!firstSignal) throw new Error("Recovery read did not receive an abort signal");
            expect(firstSignal.aborted).toBe(true);
            expect(workspaceUnavailable.renderCount).toBeGreaterThan(0);

            const retry = workspaceUnavailable.onRetry;
            if (!retry) throw new Error("Timed-out recovery did not remain unavailable");
            await act(async () => {
                await retry();
            });

            expect(rendered.readWorkspace().activeWorkspace).toEqual(newest);
            expect(router.refresh).toHaveBeenCalledOnce();

            await act(async () => {
                resolveLateResponse(jsonResponse({ workspaces: [stale], activeWorkspaceId: stale.id }));
                await Promise.resolve();
            });

            expect(rendered.readWorkspace().activeWorkspace).toEqual(newest);
            expect(router.refresh).toHaveBeenCalledOnce();
        } finally {
            resolveLateResponse(jsonResponse({ workspaces: [stale], activeWorkspaceId: stale.id }));
            await act(async () => {
                await firstRetry;
            });
            await rendered.unmount();
            vi.useRealTimers();
        }
    });

    it("forces no-provider completions through a fresh server mount without replay", () => {
        for (const relativePath of [ONBOARDING_FORM, INVITE_ACCEPT, INVITE_LINK_ACCEPT]) {
            const caller = source(relativePath);
            const successBranch = section(caller, "try {", "} catch");
            const unavailableBranch = section(
                caller,
                "if (err instanceof WorkspaceSelectionUnavailableError)",
                "toastError",
            );

            expect(successBranch).toContain('window.location.replace("/dashboard");');
            expect(successBranch).not.toContain('router.replace("/dashboard")');
            expect(successBranch).not.toContain("router.refresh");
            expect(unavailableBranch).toContain('window.location.replace("/dashboard");');
            expect(unavailableBranch).toContain("return;");
            expect(unavailableBranch).not.toContain("router.replace");
            expect(unavailableBranch).not.toContain("router.refresh");
            expect(unavailableBranch).not.toContain("setSubmitting(false)");
            expect(unavailableBranch).not.toContain("setBusy(false)");
        }
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
                identityVersion: 1,
            });
            rendered.readWorkspace().publishOrganizationIdentity({
                id: active.orgId,
                name: "New organization",
                identityVersion: 1,
            });
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

    it("rejects stale refresh identities and yields directly to a newer version", async () => {
        const active = {
            ...workspaceFixture(7, "Before"),
            role: "admin" as const,
            orgRole: "owner" as const,
        };
        const rendered = await renderInteractiveWorkspaceProvider([active], active.id);
        const canonical = {
            ...active,
            name: "After",
            timezone: "Asia/Tokyo",
            identityVersion: 1,
            orgName: "New organization",
            orgIdentityVersion: 1,
        };

        await act(async () => {
            rendered.readWorkspace().publishWorkspaceIdentity(canonical);
            rendered.readWorkspace().publishOrganizationIdentity({
                id: active.orgId,
                name: canonical.orgName,
                identityVersion: canonical.orgIdentityVersion,
            });
        });
        await rendered.rerender([active]);

        expect(rendered.readWorkspace().activeWorkspace).toMatchObject({
            name: canonical.name,
            timezone: canonical.timezone,
            orgName: canonical.orgName,
        });

        const newer = {
            ...canonical,
            name: "Later",
            identityVersion: 2,
            orgName: "Later organization",
            orgIdentityVersion: 2,
        };
        await rendered.rerender([newer]);

        expect(rendered.readWorkspace().activeWorkspace).toMatchObject({
            name: newer.name,
            orgName: newer.orgName,
        });

        await rendered.unmount();
    });

    it("releases failed optimistic identities so an authoritative refresh can replace them", async () => {
        const active = workspaceFixture(7, "Before");
        const rendered = await renderInteractiveWorkspaceProvider([active], active.id);
        const optimistic = { ...active, name: "Optimistic", orgName: "Optimistic organization" };

        await act(async () => {
            rendered.readWorkspace().publishWorkspaceIdentity(optimistic);
            rendered.readWorkspace().publishOrganizationIdentity({
                id: active.orgId,
                name: optimistic.orgName,
                identityVersion: optimistic.orgIdentityVersion,
            });
            rendered.readWorkspace().restoreWorkspaceIdentity(optimistic, active);
            rendered.readWorkspace().restoreOrganizationIdentity(
                {
                    id: active.orgId,
                    name: optimistic.orgName,
                    identityVersion: optimistic.orgIdentityVersion,
                },
                {
                    id: active.orgId,
                    name: active.orgName,
                    identityVersion: active.orgIdentityVersion,
                },
            );
        });
        const authoritative = {
            ...active,
            name: "Authoritative",
            identityVersion: 1,
            orgName: "Authoritative organization",
            orgIdentityVersion: 1,
        };
        await rendered.rerender([authoritative]);

        expect(rendered.readWorkspace().activeWorkspace).toMatchObject({
            name: authoritative.name,
            orgName: authoritative.orgName,
        });

        await rendered.unmount();
    });

    it("does not let an older failed publication clear a newer canonical identity", async () => {
        const active = workspaceFixture(7, "Before");
        const rendered = await renderInteractiveWorkspaceProvider([active], active.id);
        const optimistic = { ...active, name: "Optimistic", orgName: "Optimistic organization" };
        const canonical = {
            ...active,
            name: "Canonical",
            identityVersion: 1,
            orgName: "Canonical organization",
            orgIdentityVersion: 1,
        };

        await act(async () => {
            rendered.readWorkspace().publishWorkspaceIdentity(optimistic);
            rendered.readWorkspace().publishOrganizationIdentity({
                id: active.orgId,
                name: optimistic.orgName,
                identityVersion: optimistic.orgIdentityVersion,
            });
            rendered.readWorkspace().publishWorkspaceIdentity(canonical);
            rendered.readWorkspace().publishOrganizationIdentity({
                id: active.orgId,
                name: canonical.orgName,
                identityVersion: canonical.orgIdentityVersion,
            });
            rendered.readWorkspace().restoreWorkspaceIdentity(optimistic, active);
            rendered.readWorkspace().restoreOrganizationIdentity(
                {
                    id: active.orgId,
                    name: optimistic.orgName,
                    identityVersion: optimistic.orgIdentityVersion,
                },
                {
                    id: active.orgId,
                    name: active.orgName,
                    identityVersion: active.orgIdentityVersion,
                },
            );
        });
        await rendered.rerender([active]);

        expect(rendered.readWorkspace().activeWorkspace).toMatchObject({
            name: canonical.name,
            identityVersion: canonical.identityVersion,
            orgName: canonical.orgName,
            orgIdentityVersion: canonical.orgIdentityVersion,
        });

        await rendered.unmount();
    });

    it("keeps a newer held identity after an absent payload and a later stale appearance", async () => {
        const existing = workspaceFixture(7, "Existing");
        const canonical = {
            ...workspaceFixture(22, "Canonical"),
            identityVersion: 2,
            orgName: "Canonical organization",
            orgIdentityVersion: 3,
        };
        const rendered = await renderInteractiveWorkspaceProvider([existing], existing.id);

        await act(async () => {
            await rendered.readWorkspace().runSelectionChange(async (
                publishActiveWorkspace,
                publishWorkspace,
            ) => {
                publishWorkspace(canonical);
                publishActiveWorkspace(canonical.id);
            });
            rendered.readWorkspace().publishWorkspaceIdentity(canonical);
            rendered.readWorkspace().publishOrganizationIdentity({
                id: canonical.orgId,
                name: canonical.orgName,
                identityVersion: canonical.orgIdentityVersion,
            });
        });
        await rendered.rerender([existing]);
        const stale = {
            ...workspaceFixture(canonical.id, "Stale"),
            role: "owner" as const,
            orgName: "Stale organization",
            orgIdentityVersion: 2,
            identityVersion: 1,
        };
        await rendered.rerender([existing, stale]);

        expect(rendered.readWorkspace().workspaces.find(({ id }) => id === canonical.id))
            .toMatchObject({
                name: canonical.name,
                identityVersion: canonical.identityVersion,
                role: stale.role,
                orgName: canonical.orgName,
                orgIdentityVersion: canonical.orgIdentityVersion,
            });

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

    it("documents ordered recovery without adopting active state from refreshed props", () => {
        const provider = source(PROVIDER);
        const contract = section(provider, "/**", "export function WorkspaceProvider");
        const normalizedContract = contract.replace(/^\s*\*\s?/gm, "").replace(/\s+/g, " ");
        const api = source(API);
        const protocolStart = api.indexOf("Completes a selection-changing request");
        const protocol = api.slice(
            api.lastIndexOf("/**", protocolStart),
            api.indexOf("export async function recoverWorkspaceSelectionResponse", protocolStart),
        ).replace(/^\s*\*\s?/gm, "").replace(/\s+/g, " ");

        expect(normalizedContract).toContain("one selection-changing operation at a time");
        expect(normalizedContract).toContain("cookie is applied before that result is published");
        expect(normalizedContract).toContain("shared request pipeline re-reads");
        expect(normalizedContract).toContain("withheld until an explicit ordered retry succeeds");
        expect(normalizedContract).toContain("never repair active selection");
        expect(normalizedContract).not.toContain("matching the order in which the browser");
        expect(provider).not.toContain("setActiveWorkspaceId(initialActiveId)");
        expect(protocol).toContain("Fetch failures before a {@code Response} exists");
        expect(protocol).toContain("authoritative workspace snapshot");
        expect(protocol).toContain("surrounding selection mutex stays held");
        expect(protocol).toContain("WorkspaceSelectionUnavailableError");
    });

    it("recovers a truncated leave response before matching default and provider-derived headers", async () => {
        const active = workspaceFixture(7, "Leaving workspace");
        const remaining = workspaceFixture(22, "Remaining workspace");
        const rendered = await renderInteractiveWorkspaceProvider(
            [active, remaining],
            active.id,
            "NEXT_LOCALE=en; connex_workspace=7",
        );
        const memberRequests: Array<{ path: string; workspaceId: string | null }> = [];
        vi.stubGlobal("fetch", vi.fn(async (
            input: string | URL | Request,
            init?: RequestInit,
        ): Promise<Response> => {
            const requestUrl = input instanceof Request ? input.url : input.toString();
            const url = new URL(requestUrl, "http://localhost:8080");
            if (url.pathname === "/api/auth/csrf") {
                return new Response("", { status: 503 });
            }
            if (url.pathname === "/api/workspaces/7/leave") {
                document.cookie = "NEXT_LOCALE=en; connex_workspace=22";
                return new Response('{"activeWorkspaceId":22', {
                    status: 200,
                    headers: { "Content-Type": "application/json" },
                });
            }
            if (url.pathname === "/api/workspaces") {
                expect(new Headers(init?.headers).get("X-Workspace-Id")).toBe("22");
                return jsonResponse({ workspaces: [remaining], activeWorkspaceId: remaining.id });
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

        await act(async () => {
            await rendered.readWorkspace().runSelectionChange(async (publishActiveWorkspace) => {
                const selection = await leaveWorkspace(active.id);
                publishActiveWorkspace(selection.activeWorkspaceId);
            });
        });
        const providerWorkspaceId = rendered.readWorkspace().activeWorkspaceId;
        if (providerWorkspaceId === null) throw new Error("Recovery did not publish a workspace");
        await getActiveWorkspaceMembers();
        await getWorkspaceMembers(providerWorkspaceId, {
            headers: { "X-Workspace-Id": String(providerWorkspaceId) },
        });

        expect(document.cookie).toContain("connex_workspace=22");
        expect(providerWorkspaceId).toBe(22);
        expect(memberRequests).toEqual([
            { path: "/api/workspaces/22/members", workspaceId: "22" },
            { path: "/api/workspaces/22/members", workspaceId: "22" },
        ]);

        await rendered.unmount();
    });
});
