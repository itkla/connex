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

const DISCLOSURE = "/api/orgs/3/data-subject-requests/41/disclosure";

type Recorded = { url: string; method: string; body: unknown; csrf: string | null };

function json(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: { "Content-Type": "application/json" },
    });
}

function stubBackend(disclosure: (attempt: number) => Response): Recorded[] {
    vi.stubGlobal("window", {
        addEventListener: vi.fn(),
        crypto: { randomUUID: () => "event-id" },
        localStorage: { setItem: vi.fn() },
        location: { pathname: "/organization/privacy", search: "" },
    });
    vi.stubGlobal("document", { cookie: "connex_workspace=5; NEXT_LOCALE=en" });
    const requests: Recorded[] = [];
    let csrfIssued = 0;
    let disclosureAttempts = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
        const url = input instanceof Request ? input.url : String(input);
        const method = init?.method ?? "GET";
        const body = typeof init?.body === "string" ? JSON.parse(init.body) as unknown : undefined;
        requests.push({ url, method, body, csrf: new Headers(init?.headers).get("X-XSRF-TOKEN") });
        if (url.endsWith("/api/auth/csrf")) {
            csrfIssued += 1;
            return json({ token: `csrf-token-${csrfIssued}`, headerName: "X-XSRF-TOKEN", requestIdentity: "request-identity" });
        }
        if (url.endsWith("/api/auth/webauthn/step-up/options")) {
            return json({ challenge: "challenge" });
        }
        if (url.endsWith("/api/auth/webauthn/step-up")) {
            return json({ user: { id: 7 } });
        }
        if (url.endsWith(DISCLOSURE)) {
            disclosureAttempts += 1;
            return disclosure(disclosureAttempts);
        }
        return json({ message: "Unexpected request" }, 500);
    }));
    return requests;
}

function disclosureCalls(requests: Recorded[]): Recorded[] {
    return requests.filter(({ url }) => url.endsWith(DISCLOSURE));
}

describe("data subject disclosure API", () => {
    afterEach(() => {
        vi.resetModules();
        vi.unstubAllGlobals();
        vi.clearAllMocks();
    });

    it("assembles the disclosure with one CSRF-bearing POST and never a GET", async () => {
        const requests = stubBackend(() => json({ subject: { id: 9 } }));
        const { assembleDataSubjectDisclosure } = await import("@/app/lib/api");

        await expect(assembleDataSubjectDisclosure(3, 41)).resolves.toEqual({ subject: { id: 9 } });

        expect(disclosureCalls(requests)).toEqual([
            { url: DISCLOSURE, method: "POST", body: {}, csrf: "csrf-token-1" },
        ]);
    });

    it("retries once with a fresh CSRF token when the first token is refused", async () => {
        const requests = stubBackend((attempt) => attempt === 1
            ? new Response("", { status: 403 })
            : json({ subject: { id: 9 } }));
        const { assembleDataSubjectDisclosure } = await import("@/app/lib/api");

        await expect(assembleDataSubjectDisclosure(3, 41)).resolves.toEqual({ subject: { id: 9 } });

        expect(disclosureCalls(requests).map(({ method, csrf }) => [method, csrf])).toEqual([
            ["POST", "csrf-token-1"],
            ["POST", "csrf-token-2"],
        ]);
    });

    it("replays the POST once after a passkey step-up", async () => {
        const requests = stubBackend((attempt) => attempt === 1
            ? json({ code: "RECENT_AUTHENTICATION_REQUIRED", message: "Recent WebAuthn authentication required" }, 403)
            : json({ subject: { id: 9 } }));
        const { assembleDataSubjectDisclosure } = await import("@/app/lib/api");

        await expect(assembleDataSubjectDisclosure(3, 41)).resolves.toEqual({ subject: { id: 9 } });

        expect(startAuthentication).toHaveBeenCalledOnce();
        expect(requests.filter(({ method }) => method !== "GET").map(({ url, method }) => [url, method])).toEqual([
            [DISCLOSURE, "POST"],
            ["/api/auth/webauthn/step-up/options", "POST"],
            ["/api/auth/webauthn/step-up", "POST"],
            [DISCLOSURE, "POST"],
        ]);
    });

    it("surfaces an unavailable audit as an error without retrying the assembly", async () => {
        const requests = stubBackend(() => json({ message: "Service unavailable" }, 503));
        const { ApiError, assembleDataSubjectDisclosure } = await import("@/app/lib/api");

        const failure = await assembleDataSubjectDisclosure(3, 41).catch((error: unknown) => error);

        expect(failure).toBeInstanceOf(ApiError);
        expect(failure).toMatchObject({ status: 503 });
        expect(disclosureCalls(requests)).toHaveLength(1);
    });
});
