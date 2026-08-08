import { afterEach, describe, expect, it, vi } from "vitest";

const startAuthentication = vi.hoisted(() => vi.fn(async () => ({
    id: "credential-id",
    rawId: "credential-id",
    response: {
        authenticatorData: "authenticator-data",
        clientDataJSON: "client-data",
        signature: "signature",
    },
    type: "public-key",
    clientExtensionResults: {},
    authenticatorAttachment: "platform",
})));

vi.mock("@simplewebauthn/browser", () => ({ startAuthentication }));

function json(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: { "Content-Type": "application/json" },
    });
}

describe("tenant lifecycle API", () => {
    afterEach(() => {
        vi.resetModules();
        vi.unstubAllGlobals();
        vi.clearAllMocks();
    });

    it("uses the export POST to trigger passkey step-up without an unrelated mutation", async () => {
        vi.stubGlobal("window", {
            addEventListener: vi.fn(),
            crypto: { randomUUID: () => "event-id" },
            localStorage: { setItem: vi.fn() },
            location: { pathname: "/organization/overview" },
        });
        vi.stubGlobal("document", {
            cookie: "connex_workspace=5; NEXT_LOCALE=en",
        });
        let exportAttempts = 0;
        const requests: Array<{ url: string; method: string }> = [];
        const fetchMock = vi.fn(async (
            input: string | URL | Request,
            init?: RequestInit,
        ): Promise<Response> => {
            const url = input instanceof Request ? input.url : String(input);
            const method = init?.method ?? "GET";
            requests.push({ url, method });
            if (url.endsWith("/api/auth/csrf")) {
                return json({
                    token: "csrf-token",
                    headerName: "X-XSRF-TOKEN",
                    requestIdentity: "request-identity",
                });
            }
            if (url.endsWith("/api/auth/webauthn/step-up/options")) {
                return json({ challenge: "challenge" });
            }
            if (url.endsWith("/api/auth/webauthn/step-up")) {
                return json({ user: { id: 7 } });
            }
            if (url.endsWith("/api/orgs/3/workspaces/5/export")) {
                exportAttempts += 1;
                if (exportAttempts === 1) {
                    return json({
                        code: "RECENT_AUTHENTICATION_REQUIRED",
                        message: "Recent WebAuthn authentication required",
                    }, 403);
                }
                return json({
                    expiresAt: "2026-08-08T12:02:00Z",
                    downloadPath: "/api/orgs/3/workspaces/5/export",
                });
            }
            return json({ message: "Unexpected request" }, 500);
        });
        vi.stubGlobal("fetch", fetchMock);
        const { requestWorkspaceTenantExport } = await import("@/app/lib/api");

        await expect(requestWorkspaceTenantExport(3, 5)).resolves.toEqual({
            expiresAt: "2026-08-08T12:02:00Z",
            downloadPath: "/api/orgs/3/workspaces/5/export",
        });

        expect(startAuthentication).toHaveBeenCalledOnce();
        expect(requests.filter(({ method }) => method !== "GET")).toEqual([
            { url: "/api/orgs/3/workspaces/5/export", method: "POST" },
            { url: "/api/auth/webauthn/step-up/options", method: "POST" },
            { url: "/api/auth/webauthn/step-up", method: "POST" },
            { url: "/api/orgs/3/workspaces/5/export", method: "POST" },
        ]);
        expect(requests.some(({ url }) => url.includes("credentials"))).toBe(false);
    });
});
