import { readFileSync } from "node:fs";
import path from "node:path";
import type { AuthenticationResponseJSON } from "@simplewebauthn/browser";
import { afterEach, describe, expect, it, vi } from "vitest";

import { settingsDestination } from "@/app/lib/settingsEntryPoints";

const startAuthentication = vi.hoisted(() => vi.fn(async (): Promise<AuthenticationResponseJSON> => ({
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

const FILTER_SOURCE = path.resolve(
    process.cwd(),
    "..",
    "backend",
    "src",
    "main",
    "java",
    "ooo",
    "klae",
    "connex",
    "backend",
    "config",
    "PrivilegedMfaEnforcementFilter.java",
);
const CLIENT_SOURCE = path.resolve(process.cwd(), "app", "lib", "api.ts");

type Recorded = { url: string; method: string; body: unknown };

function json(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: { "Content-Type": "application/json" },
    });
}

function stubBrowser(location: { pathname: string; search?: string }) {
    vi.stubGlobal("window", {
        addEventListener: vi.fn(),
        crypto: { randomUUID: () => "event-id" },
        localStorage: { setItem: vi.fn() },
        location: { search: "", ...location },
    });
    vi.stubGlobal("document", { cookie: "connex_workspace=5; NEXT_LOCALE=en" });
}

function stubBackend(route: (url: string, method: string) => Response | null): Recorded[] {
    const requests: Recorded[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
        const url = input instanceof Request ? input.url : String(input);
        const method = init?.method ?? "GET";
        const body = typeof init?.body === "string" ? JSON.parse(init.body) as unknown : undefined;
        requests.push({ url, method, body });
        if (url.endsWith("/api/auth/csrf")) {
            return json({ token: "csrf-token", headerName: "X-XSRF-TOKEN", requestIdentity: "request-identity" });
        }
        return route(url, method) ?? json({ message: "Unexpected request" }, 500);
    }));
    return requests;
}

/** The string literals of one Java `Set.of(...)` or TypeScript `new Set([...])` declaration. */
function declaredPaths(source: string, declaration: string): string[] {
    const start = source.indexOf(declaration);
    if (start === -1) throw new Error(`${declaration} is gone`);
    const end = source.indexOf(";", start);
    return [...source.slice(start, end).matchAll(/"(\/api\/[^"]*)"/g)].map((match) => match[1]).sort();
}

describe("passkey approval code requests", () => {
    afterEach(() => {
        vi.resetModules();
        vi.unstubAllGlobals();
        vi.clearAllMocks();
    });

    it("creates and turns off workspace and organization codes on the member's own routes", async () => {
        stubBrowser({ pathname: "/settings/workspace/people" });
        const requests = stubBackend((url, method) => {
            if (method === "POST" && url.endsWith("/mfa-attestations")) {
                return json({ code: "ABCD-EFGH-JKMN-PQRS", expiresAt: "2026-10-09T07:00:00Z" }, 201);
            }
            if (method === "DELETE" && url.endsWith("/mfa-attestations")) {
                return new Response(null, { status: 204 });
            }
            return null;
        });
        const api = await import("@/app/lib/api");

        await expect(api.issueWorkspaceMfaAttestation(5, 9)).resolves.toEqual({
            code: "ABCD-EFGH-JKMN-PQRS",
            expiresAt: "2026-10-09T07:00:00Z",
        });
        await api.revokeWorkspaceMfaAttestation(5, 9);
        await api.issueOrgMfaAttestation(3, 9);
        await api.revokeOrgMfaAttestation(3, 9);

        expect(requests.filter(({ method }) => method !== "GET").map(({ url, method }) => ({ url, method })))
            .toEqual([
                { url: "/api/workspaces/5/members/9/mfa-attestations", method: "POST" },
                { url: "/api/workspaces/5/members/9/mfa-attestations", method: "DELETE" },
                { url: "/api/orgs/3/members/9/mfa-attestations", method: "POST" },
                { url: "/api/orgs/3/members/9/mfa-attestations", method: "DELETE" },
            ]);
    });

    it("rides the client's passkey step-up when the server wants a fresh check before creating a code", async () => {
        stubBrowser({ pathname: "/settings/workspace/people" });
        let attempts = 0;
        const requests = stubBackend((url) => {
            if (url.endsWith("/api/auth/webauthn/step-up/options")) return json({ challenge: "challenge" });
            if (url.endsWith("/api/auth/webauthn/step-up")) return json({ user: { id: 7 } });
            if (url.endsWith("/api/workspaces/5/members/9/mfa-attestations")) {
                attempts += 1;
                return attempts === 1
                    ? json({ code: "RECENT_AUTHENTICATION_REQUIRED", message: "Recent authentication required" }, 403)
                    : json({ code: "ABCD-EFGH-JKMN-PQRS", expiresAt: "2026-10-09T07:00:00Z" }, 201);
            }
            return null;
        });
        const { issueWorkspaceMfaAttestation } = await import("@/app/lib/api");

        await expect(issueWorkspaceMfaAttestation(5, 9)).resolves.toMatchObject({ code: "ABCD-EFGH-JKMN-PQRS" });

        expect(startAuthentication).toHaveBeenCalledOnce();
        expect(requests.filter(({ method }) => method !== "GET").map(({ url }) => url)).toEqual([
            "/api/workspaces/5/members/9/mfa-attestations",
            "/api/auth/webauthn/step-up/options",
            "/api/auth/webauthn/step-up",
            "/api/workspaces/5/members/9/mfa-attestations",
        ]);
    });

    it("redeems a code with the assertion of the passkey it approves", async () => {
        stubBrowser({ pathname: "/settings/personal/security" });
        const requests = stubBackend((url) => {
            if (url.endsWith("/api/auth/webauthn/attestation/options")) return json({ challenge: "challenge" });
            if (url.endsWith("/api/auth/webauthn/attestation")) return json({ orgId: 3 });
            return null;
        });
        const { beginMfaAttestation, redeemMfaAttestation } = await import("@/app/lib/api");

        await beginMfaAttestation();
        const credential = await startAuthentication();
        await expect(redeemMfaAttestation("ABCD-EFGH-JKMN-PQRS", credential)).resolves.toEqual({ orgId: 3 });

        const posts = requests.filter(({ method }) => method === "POST");
        expect(posts.map(({ url }) => url)).toEqual([
            "/api/auth/webauthn/attestation/options",
            "/api/auth/webauthn/attestation",
        ]);
        expect(posts[1].body).toEqual({ code: "ABCD-EFGH-JKMN-PQRS", credential });
    });

    it("lets an account confined to passkey enrollment redeem a code, and nothing else new", async () => {
        stubBrowser({ pathname: settingsDestination("personal.security").href, search: "?mfa=enroll" });
        const requests = stubBackend((url) => {
            if (url.endsWith("/api/auth/webauthn/attestation/options")) return json({ challenge: "challenge" });
            if (url.endsWith("/api/auth/webauthn/attestation")) return json({ orgId: 3 });
            return null;
        });
        const api = await import("@/app/lib/api");

        await api.beginMfaAttestation();
        await expect(api.redeemMfaAttestation("ABCD-EFGH-JKMN-PQRS", await startAuthentication()))
            .resolves.toEqual({ orgId: 3 });
        await expect(api.issueWorkspaceMfaAttestation(5, 9)).rejects.toMatchObject({
            status: 403,
            code: api.PRIVILEGED_MFA_ENROLLMENT_REQUIRED_CODE,
        });

        expect(requests.some(({ url }) => url.endsWith("/mfa-attestations"))).toBe(false);
    });

    it("keeps the client's confinement allowlist exactly the server filter's", () => {
        const filter = readFileSync(FILTER_SOURCE, "utf8");
        const client = readFileSync(CLIENT_SOURCE, "utf8");

        expect(declaredPaths(client, "const PRIVILEGED_MFA_ENROLLMENT_GET_PATHS"))
            .toEqual(declaredPaths(filter, "Set<String> ENROLLMENT_GET_PATHS"));
        expect(declaredPaths(client, "const PRIVILEGED_MFA_ENROLLMENT_POST_PATHS"))
            .toEqual(declaredPaths(filter, "Set<String> ENROLLMENT_POST_PATHS"));
        expect(declaredPaths(client, "const PRIVILEGED_MFA_ENROLLMENT_POST_PATHS")).toEqual(expect.arrayContaining([
            "/api/auth/webauthn/attestation",
            "/api/auth/webauthn/attestation/options",
        ]));
    });
});
