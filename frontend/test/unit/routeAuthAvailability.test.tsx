import { Suspense, isValidElement, type ReactElement, type ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import EditDocumentTemplatePage from "@/app/(app)/library/documents/[id]/page";
import FilesLibraryPage from "@/app/(app)/library/files/page";
import DealsPage from "@/app/(app)/records/deals/page";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import TemplateBuilder from "@/app/components/library/documents/TemplateBuilder";
import FilesBrowser from "@/app/components/library/files/FilesBrowser";

const { redirectMock } = vi.hoisted(() => ({
    redirectMock: vi.fn((destination: string): never => {
        throw new Error(`redirect:${destination}`);
    }),
}));

vi.mock("next/headers", () => ({
    headers: () => Promise.resolve(new Headers({
        cookie: "JSESSIONID=session; connex_workspace=7",
    })),
}));

vi.mock("next/navigation", async (importOriginal) => {
    const actual = await importOriginal<typeof import("next/navigation")>();
    return { ...actual, redirect: redirectMock };
});

vi.mock("next-intl/server", () => ({
    getTranslations: () => Promise.resolve((key: string) => key),
}));

type AuthenticationResponse = "authenticated" | 401 | 503;

function json(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: { "Content-Type": "application/json" },
    });
}

function stubRouteReads(authenticationResponse: AuthenticationResponse) {
    const fetch = vi.fn((input: string | URL | Request) => {
        const url = String(input);
        if (url.endsWith("/api/auth/me")) {
            return Promise.resolve(authenticationResponse === "authenticated"
                ? json({ id: 9, email: "member@connex.test", locale: "en" })
                : new Response("", { status: authenticationResponse }));
        }
        if (url.endsWith("/api/workspaces")) {
            return Promise.resolve(new Response("", { status: 503 }));
        }
        if (url.endsWith("/api/document-templates/7")) {
            return Promise.resolve(json({ id: 7, name: "Quarterly review" }));
        }
        if (url.endsWith("/api/invite-links/return-token")) {
            return Promise.resolve(json({
                valid: true,
                workspaceName: "Established workspace",
                role: "member",
            }));
        }
        return Promise.resolve(new Response("", { status: 404 }));
    });
    vi.stubGlobal("fetch", fetch);
    return fetch;
}

function hasChildren(value: unknown): value is { children?: ReactNode } {
    return typeof value === "object" && value !== null && "children" in value;
}

function findByType(node: ReactNode, type: unknown): ReactElement | null {
    if (Array.isArray(node)) {
        for (const child of node) {
            const found = findByType(child, type);
            if (found !== null) return found;
        }
        return null;
    }
    if (!isValidElement(node)) return null;
    if (node.type === type) return node;
    return hasChildren(node.props) ? findByType(node.props.children, type) : null;
}

afterEach(() => {
    vi.unstubAllGlobals();
    redirectMock.mockClear();
});

describe("a list route distinguishes an expired session from unavailable auth", () => {
    it("redirects a 401 to the existing login target", async () => {
        stubRouteReads(401);

        await expect(FilesLibraryPage()).rejects.toThrow("redirect:/auth/login");
        expect(redirectMock).toHaveBeenCalledWith("/auth/login");
    });

    it("renders the retryable unavailable state for a 503", async () => {
        stubRouteReads(503);

        const rendered = await FilesLibraryPage();

        expect(isValidElement(rendered) ? rendered.type : null).toBe(WorkspaceUnavailablePage);
        expect(redirectMock).not.toHaveBeenCalled();
    });

    it("renders the normal list for an authenticated user", async () => {
        stubRouteReads("authenticated");

        const rendered = await FilesLibraryPage();

        expect(isValidElement(rendered) ? rendered.type : null).toBe(Suspense);
        expect(findByType(rendered, FilesBrowser)).not.toBeNull();
        expect(redirectMock).not.toHaveBeenCalled();
    });
});

describe("route authentication is a preflight for protected page reads", () => {
    it("renders unavailable before a failing workspace read can mask a 503 auth result", async () => {
        const fetch = stubRouteReads(503);

        const rendered = await DealsPage({ searchParams: Promise.resolve({}) });

        expect(isValidElement(rendered) ? rendered.type : null).toBe(WorkspaceUnavailablePage);
        expect(fetch.mock.calls.some(([input]) => String(input).endsWith("/api/workspaces")))
            .toBe(false);
        expect(redirectMock).not.toHaveBeenCalled();
    });

    it("redirects before a failing workspace read can mask a 401 auth result", async () => {
        const fetch = stubRouteReads(401);

        await expect(DealsPage({ searchParams: Promise.resolve({}) }))
            .rejects.toThrow("redirect:/auth/login");
        expect(fetch.mock.calls.some(([input]) => String(input).endsWith("/api/workspaces")))
            .toBe(false);
        expect(redirectMock).toHaveBeenCalledWith("/auth/login");
    });
});

describe("a dynamic detail route distinguishes an expired session from unavailable auth", () => {
    it("redirects a 401 to the existing login target", async () => {
        stubRouteReads(401);

        await expect(EditDocumentTemplatePage({ params: Promise.resolve({ id: "7" }) }))
            .rejects.toThrow("redirect:/auth/login");
        expect(redirectMock).toHaveBeenCalledWith("/auth/login");
    });

    it("renders the retryable unavailable state for a 503", async () => {
        stubRouteReads(503);

        const rendered = await EditDocumentTemplatePage({ params: Promise.resolve({ id: "7" }) });

        expect(isValidElement(rendered) ? rendered.type : null).toBe(WorkspaceUnavailablePage);
        expect(redirectMock).not.toHaveBeenCalled();
    });

    it("renders the normal detail view for an authenticated user", async () => {
        stubRouteReads("authenticated");

        const rendered = await EditDocumentTemplatePage({ params: Promise.resolve({ id: "7" }) });

        expect(isValidElement(rendered) ? rendered.type : null).toBe(TemplateBuilder);
        expect(redirectMock).not.toHaveBeenCalled();
    });
});
