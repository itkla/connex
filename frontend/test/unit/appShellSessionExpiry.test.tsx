import { isValidElement } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import AppLayout from "@/app/(app)/layout";
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

function stubShellReads(workspacesStatus: number) {
    vi.stubGlobal("fetch", vi.fn((input: string | URL | Request) => {
        const url = String(input);
        if (url.endsWith("/api/auth/me")) {
            return Promise.resolve(json({ id: 9, email: "member@connex.test", locale: "en" }));
        }
        if (url.endsWith("/api/workspaces")) {
            return Promise.resolve(new Response("", { status: workspacesStatus }));
        }
        return Promise.resolve(new Response("", { status: 503 }));
    }));
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
        stubShellReads(503);

        const rendered = await AppLayout({ children: null });

        expect(isValidElement(rendered) ? rendered.type : null).toBe(WorkspaceUnavailablePage);
        expect(redirectMock).not.toHaveBeenCalled();
    });
});

describe("the unavailable workspace state is escapable", () => {
    it("offers sign-out, the one recovery a repeated rejection cannot defeat", async () => {
        const rendered = await WorkspaceUnavailablePage();

        expect(actionHrefs(rendered)).toContain("/auth/logout");
    });
});
