import { createElement, Fragment, isValidElement } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import AppLayout from "@/app/(app)/layout";
import BrowserAccountBridge from "@/app/components/BrowserAccountBridge";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";

const { redirectMock } = vi.hoisted(() => ({
    redirectMock: vi.fn((destination: string): never => {
        throw new Error(`redirect:${destination}`);
    }),
}));

vi.mock("next/headers", () => ({
    headers: () => Promise.resolve(new Headers({
        cookie: "JSESSIONID=stale; connex_workspace=7",
        "x-pathname": "/records/contacts",
    })),
}));

vi.mock("next/navigation", async (importOriginal) => {
    const actual = await importOriginal<typeof import("next/navigation")>();
    return { ...actual, redirect: redirectMock };
});

vi.mock("next-intl/server", () => ({
    getTranslations: () => Promise.resolve((key: string) => key),
}));

function json(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: { "Content-Type": "application/json" },
    });
}

function stubShellReads(workspacesStatus: number, authenticationStatus: number = 200) {
    const fetch = vi.fn((input: string | URL | Request) => {
        const url = String(input);
        if (url.endsWith("/api/auth/me")) {
            if (authenticationStatus !== 200) {
                return Promise.resolve(new Response("", { status: authenticationStatus }));
            }
            return Promise.resolve(json({ id: 9, email: "member@connex.test", locale: "en" }));
        }
        if (url.endsWith("/api/workspaces")) {
            return Promise.resolve(new Response("", { status: workspacesStatus }));
        }
        return Promise.resolve(new Response("", { status: 503 }));
    });
    vi.stubGlobal("fetch", fetch);
    return fetch;
}

function actionHrefs(node: unknown): string[] {
    if (!isValidElement(node)) return [];
    const props: unknown = node.props;
    if (typeof props !== "object" || props === null || !("actions" in props)) return [];
    const { actions } = props;
    if (!Array.isArray(actions)) return [];
    return actions.flatMap((action: unknown) => (
        typeof action === "object" && action !== null
            && "href" in action && typeof action.href === "string"
            ? [action.href]
            : []
    ));
}

afterEach(() => {
    vi.unstubAllGlobals();
    redirectMock.mockClear();
});

describe("the app shell distinguishes a rejected session from an unavailable workspace read", () => {
    it("sends a rejected session to sign in, keeping the requested path", async () => {
        stubShellReads(401);

        await expect(AppLayout({ children: null }))
            .rejects.toThrow("redirect:/auth/login?redirect=%2Frecords%2Fcontacts");
        expect(redirectMock).toHaveBeenCalledWith("/auth/login?redirect=%2Frecords%2Fcontacts");
    });

    it("keeps the retryable unavailable state for a backend fault", async () => {
        const fetch = stubShellReads(503, 503);

        const rendered = await AppLayout({ children: null });

        expect(isValidElement(rendered) ? rendered.type : null).toBe(WorkspaceUnavailablePage);
        expect(redirectMock).not.toHaveBeenCalled();
        expect(fetch.mock.calls.some(([input]) => String(input).endsWith("/api/workspaces")))
            .toBe(false);
    });

    it("synchronizes the resolved account when the workspace read is unavailable", async () => {
        const fetch = stubShellReads(503);
        const workspaceScopedContent = createElement("div", { "data-workspace-scoped": true }, "Workspace data");

        const rendered = await AppLayout({ children: workspaceScopedContent });

        expect(rendered).toEqual(createElement(Fragment, null,
            createElement(BrowserAccountBridge, { userId: 9 }),
            createElement(WorkspaceUnavailablePage),
        ));
        expect(redirectMock).not.toHaveBeenCalled();
        expect(fetch.mock.calls.some(([input]) => String(input).endsWith("/api/permissions/effective")))
            .toBe(false);
    });
});

describe("the unavailable workspace state is escapable", () => {
    it("offers sign-out, the one recovery a repeated rejection cannot defeat", async () => {
        const rendered = await WorkspaceUnavailablePage();

        expect(actionHrefs(rendered)).toContain("/auth/logout");
    });
});
