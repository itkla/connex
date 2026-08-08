import { readFileSync } from "node:fs";
import path from "node:path";
import { createElement, isValidElement } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import AppLayout from "@/app/(app)/layout";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import OnboardingForm from "@/app/onboarding/OnboardingForm";
import OnboardingPage from "@/app/onboarding/page";
import {
    getCurrentUserResultFromCookie,
    getMyWorkspacesFromCookie,
    getMyWorkspacesResultFromCookie,
} from "@/app/lib/api";
import enErrors from "@/messages/en/errors.json";
import jaErrors from "@/messages/ja/errors.json";

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

type WorkspaceResponse = "unavailable" | "empty" | "ready";
type AuthenticationResponse = "authenticated" | "network" | 401 | 403 | 503;

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), "utf8");
}

function hasChildren(value: unknown): value is { children?: unknown } {
    return typeof value === "object" && value !== null && "children" in value;
}

function json(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: { "Content-Type": "application/json" },
    });
}

function stubAppShellReads(
    workspaceResponse: WorkspaceResponse,
    authenticationResponse: AuthenticationResponse = "authenticated",
) {
    const fetch = vi.fn((input: string | URL | Request) => {
        const url = String(input);
        if (url.endsWith("/api/auth/me")) {
            if (authenticationResponse === "network") {
                return Promise.reject(new TypeError("fetch failed"));
            }
            if (authenticationResponse !== "authenticated") {
                return Promise.resolve(new Response("", { status: authenticationResponse }));
            }
            return Promise.resolve(json({ id: 9, locale: "en", timezone: "Pacific/Honolulu" }));
        }
        if (url.endsWith("/api/workspaces")) {
            if (workspaceResponse === "unavailable") {
                return Promise.resolve(new Response("", { status: 503 }));
            }
            if (workspaceResponse === "empty") {
                return Promise.resolve(json({ workspaces: [], activeWorkspaceId: null }));
            }
            return Promise.resolve(json({
                workspaces: [{
                    id: 7,
                    name: "Established workspace",
                    slug: "established-workspace",
                    timezone: "Pacific/Honolulu",
                    identityVersion: 1,
                    role: "owner",
                    orgId: 3,
                    orgName: "Connex",
                    orgIdentityVersion: 1,
                    orgRole: "owner",
                }],
                activeWorkspaceId: 7,
            }));
        }
        if (url.endsWith("/api/capabilities")) {
            return Promise.resolve(json({
                sso: false,
                socialLogin: { google: false, microsoft: false },
                connectedAccounts: { google: false, microsoft: false },
                connectedCapture: { google: false, microsoft: false },
                mailManaged: false,
                businessCardScanning: false,
                businessCardImport: false,
                campaignDelivery: false,
            }));
        }
        if (url.endsWith("/api/permissions/effective")) {
            return Promise.resolve(json([]));
        }
        return Promise.resolve(new Response("", { status: 404 }));
    });
    vi.stubGlobal("fetch", fetch);
    return fetch;
}

afterEach(() => {
    vi.unstubAllGlobals();
    redirectMock.mockClear();
});

describe("workspace snapshot reads", () => {
    it("surfaces a backend failure instead of fabricating an empty snapshot", async () => {
        vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(
            JSON.stringify({ message: "Temporarily unavailable" }),
            {
                status: 503,
                headers: { "Content-Type": "application/json" },
            },
        )));

        await expect(getMyWorkspacesFromCookie(
            "JSESSIONID=session; connex_workspace=7",
        )).rejects.toMatchObject({ status: 503 });
    });

    it("reports a backend failure as unavailable through the result-returning read", async () => {
        vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 503 })));

        await expect(getMyWorkspacesResultFromCookie("JSESSIONID=session; connex_workspace=7"))
            .resolves.toEqual({ ok: false });
    });

    it("keeps a genuinely empty backend response distinct from an unavailable response", async () => {
        vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({
            workspaces: [],
            activeWorkspaceId: null,
        })));

        await expect(getMyWorkspacesResultFromCookie("JSESSIONID=session; connex_workspace=7"))
            .resolves.toEqual({
                ok: true,
                data: { workspaces: [], activeWorkspaceId: null },
            });
    });

    it("returns an empty snapshot only when there is no authenticated cookie to forward", async () => {
        const fetch = vi.fn();
        vi.stubGlobal("fetch", fetch);

        await expect(getMyWorkspacesFromCookie(null)).resolves.toEqual({
            workspaces: [],
            activeWorkspaceId: null,
        });
        expect(fetch).not.toHaveBeenCalled();
    });

    it("reports a missing cookie as unavailable when a caller needs an honest result", async () => {
        const fetch = vi.fn();
        vi.stubGlobal("fetch", fetch);

        await expect(getMyWorkspacesResultFromCookie(null)).resolves.toEqual({ ok: false });
        expect(fetch).not.toHaveBeenCalled();
    });
});

describe("authenticated user snapshot reads", () => {
    it("reports a successful authentication check with the resolved user", async () => {
        stubAppShellReads("ready");

        await expect(getCurrentUserResultFromCookie("JSESSIONID=session; connex_workspace=7"))
            .resolves.toEqual({
                ok: true,
                data: { id: 9, locale: "en", timezone: "Pacific/Honolulu" },
            });
    });

    it("reports a 401 as an unauthenticated decision", async () => {
        stubAppShellReads("ready", 401);

        await expect(getCurrentUserResultFromCookie("JSESSIONID=session; connex_workspace=7"))
            .resolves.toEqual({ ok: true, data: null });
    });

    it("reports a 503 as unavailable", async () => {
        stubAppShellReads("ready", 503);

        await expect(getCurrentUserResultFromCookie("JSESSIONID=session; connex_workspace=7"))
            .resolves.toEqual({ ok: false });
    });

    it("does not treat a 403 as an expired session", async () => {
        stubAppShellReads("ready", 403);

        await expect(getCurrentUserResultFromCookie("JSESSIONID=session; connex_workspace=7"))
            .resolves.toEqual({ ok: false });
    });

    it("reports a network failure as unavailable", async () => {
        stubAppShellReads("ready", "network");

        await expect(getCurrentUserResultFromCookie("JSESSIONID=session; connex_workspace=7"))
            .resolves.toEqual({ ok: false });
    });
});

describe("the app shell distinguishes workspace membership from lookup availability", () => {
    it("redirects a 401 authentication decision before attempting workspace reads", async () => {
        const fetch = stubAppShellReads("ready", 401);

        await expect(AppLayout({ children: createElement("div") }))
            .rejects.toThrow("redirect:/auth/login?redirect=%2Fdashboard");
        expect(redirectMock).toHaveBeenCalledWith("/auth/login?redirect=%2Fdashboard");
        expect(fetch.mock.calls.some(([input]) => String(input).endsWith("/api/workspaces")))
            .toBe(false);
    });

    it("renders the unavailable branch for a 503 authentication check", async () => {
        const fetch = stubAppShellReads("ready", 503);
        const workspaceScopedContent = createElement("div", { "data-workspace-scoped": true });

        const rendered = await AppLayout({ children: workspaceScopedContent });

        expect(isValidElement(rendered) ? rendered.type : null).toBe(WorkspaceUnavailablePage);
        expect(redirectMock).not.toHaveBeenCalled();
        expect(fetch.mock.calls.some(([input]) => String(input).endsWith("/api/workspaces")))
            .toBe(false);
    });

    it("renders the unavailable branch for a network authentication failure", async () => {
        const fetch = stubAppShellReads("ready", "network");
        const workspaceScopedContent = createElement("div", { "data-workspace-scoped": true });

        const rendered = await AppLayout({ children: workspaceScopedContent });

        expect(isValidElement(rendered) ? rendered.type : null).toBe(WorkspaceUnavailablePage);
        expect(redirectMock).not.toHaveBeenCalled();
        expect(fetch.mock.calls.some(([input]) => String(input).endsWith("/api/workspaces")))
            .toBe(false);
    });

    it("renders the unavailable branch without redirecting or exposing workspace-scoped content", async () => {
        const fetch = stubAppShellReads("unavailable");
        const workspaceScopedContent = createElement("div", { "data-workspace-scoped": true }, "Workspace data");

        const rendered = await AppLayout({ children: workspaceScopedContent });

        expect(isValidElement(rendered) ? rendered.type : null).toBe(WorkspaceUnavailablePage);
        expect(isValidElement(rendered) && hasChildren(rendered.props)
            ? rendered.props.children
            : undefined).toBeUndefined();
        expect(redirectMock).not.toHaveBeenCalled();
        expect(fetch.mock.calls.some(([input]) => String(input).endsWith("/api/permissions/effective")))
            .toBe(false);
    });

    it("redirects to onboarding only after a resolved, genuinely empty response", async () => {
        stubAppShellReads("empty");

        await expect(AppLayout({ children: createElement("div") })).rejects.toThrow("redirect:/onboarding");
        expect(redirectMock).toHaveBeenCalledWith("/onboarding");
    });

    it("renders the authenticated shell for a resolved non-empty response", async () => {
        stubAppShellReads("ready");
        const workspaceScopedContent = createElement("div", { "data-workspace-scoped": true }, "Workspace data");

        const rendered = await AppLayout({ children: workspaceScopedContent });

        expect(isValidElement(rendered) ? rendered.type : null).not.toBe(WorkspaceUnavailablePage);
        expect(redirectMock).not.toHaveBeenCalled();
        expect(JSON.stringify(rendered)).toContain("data-workspace-scoped");
    });

    it("ships truthful retry copy in English and Japanese", () => {
        for (const key of ["title", "body", "retry", "retrying"] as const) {
            expect(enErrors.WorkspaceUnavailable[key].length).toBeGreaterThan(0);
            expect(jaErrors.WorkspaceUnavailable[key]).not.toBe(enErrors.WorkspaceUnavailable[key]);
            expect(jaErrors.WorkspaceUnavailable[key]).toMatch(/[^\x00-\x7F]/);
        }
        expect(enErrors.WorkspaceUnavailable.title).toMatch(/couldn't check/i);
        expect(enErrors.WorkspaceUnavailable.body).not.toMatch(/no workspaces|access denied/i);
    });

    it("offers an in-place reload without adding workspace-scoped shell chrome", () => {
        const page = source("app/components/WorkspaceUnavailablePage.tsx");
        const retry = source("app/components/WorkspaceUnavailableRetry.tsx");

        expect(page).toContain("<PermissionsUnavailable");
        expect(page).toContain("<WorkspaceUnavailableRetry");
        expect(retry).toContain("startTransition(() => router.refresh())");
        expect(retry).toContain("disabled={isRetrying}");
    });
});

describe("onboarding only claims a user has no workspaces after a resolved lookup", () => {
    it("renders the unavailable state for a 503 authentication check", async () => {
        const fetch = stubAppShellReads("empty", 503);

        const rendered = await OnboardingPage();

        expect(isValidElement(rendered) ? rendered.type : null).toBe(WorkspaceUnavailablePage);
        expect(isValidElement(rendered) ? rendered.type : null).not.toBe(OnboardingForm);
        expect(redirectMock).not.toHaveBeenCalled();
        expect(fetch.mock.calls.some(([input]) => String(input).endsWith("/api/workspaces")))
            .toBe(false);
    });

    it("renders the unavailable state for a network authentication failure", async () => {
        const fetch = stubAppShellReads("empty", "network");

        const rendered = await OnboardingPage();

        expect(isValidElement(rendered) ? rendered.type : null).toBe(WorkspaceUnavailablePage);
        expect(isValidElement(rendered) ? rendered.type : null).not.toBe(OnboardingForm);
        expect(redirectMock).not.toHaveBeenCalled();
        expect(fetch.mock.calls.some(([input]) => String(input).endsWith("/api/workspaces")))
            .toBe(false);
    });

    it("renders the unavailable state instead of onboarding during a transient failure", async () => {
        stubAppShellReads("unavailable");

        const rendered = await OnboardingPage();

        expect(isValidElement(rendered) ? rendered.type : null).toBe(WorkspaceUnavailablePage);
        expect(isValidElement(rendered) ? rendered.type : null).not.toBe(OnboardingForm);
        expect(redirectMock).not.toHaveBeenCalled();
    });

    it("renders onboarding after a resolved empty response", async () => {
        stubAppShellReads("empty");

        const rendered = await OnboardingPage();

        expect(isValidElement(rendered) ? rendered.type : null).toBe(OnboardingForm);
        expect(redirectMock).not.toHaveBeenCalled();
    });

    it("redirects an established user after a resolved non-empty response", async () => {
        stubAppShellReads("ready");

        await expect(OnboardingPage()).rejects.toThrow("redirect:/dashboard");
        expect(redirectMock).toHaveBeenCalledWith("/dashboard");
    });

    it("redirects an expired session to login before attempting the workspace read", async () => {
        const fetch = stubAppShellReads("empty", 401);

        await expect(OnboardingPage()).rejects.toThrow("redirect:/auth/login");
        expect(redirectMock).toHaveBeenCalledWith("/auth/login");
        expect(fetch.mock.calls.some(([input]) => String(input).endsWith("/api/workspaces")))
            .toBe(false);
    });
});
