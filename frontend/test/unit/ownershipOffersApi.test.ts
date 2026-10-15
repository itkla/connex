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

type Recorded = { url: string; method: string; body: unknown };

const OFFER = {
    id: "9007199254740993",
    scopeKind: "WORKSPACE",
    orgId: 3,
    workspaceId: 5,
    offerorUserId: 7,
    offereeUserId: 11,
    state: "OPEN",
    createdAt: "2026-10-14T00:00:00Z",
    expiresAt: "2026-10-21T00:00:00Z",
    closedAt: null,
    closedByUserId: null,
    closureReason: null,
    scopeName: "Acme",
    offerorDisplayName: "Riley",
    offereeDisplayName: "Dana",
    offereeEmail: "dana@example.com",
};

const MEMBER = {
    id: 11,
    username: "dana",
    displayName: "Dana",
    email: "dana@example.com",
    role: "member",
    builtInRole: "member",
};

function json(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: { "Content-Type": "application/json" },
    });
}

function stubBackend(route: (url: string, method: string) => Response | null): Recorded[] {
    vi.stubGlobal("window", {
        addEventListener: vi.fn(),
        crypto: { randomUUID: () => "event-id" },
        localStorage: { setItem: vi.fn() },
        location: { pathname: "/settings/personal/workspaces", search: "" },
    });
    vi.stubGlobal("document", { cookie: "connex_workspace=5; NEXT_LOCALE=en" });
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

function mutations(requests: Recorded[]): Recorded[] {
    return requests.filter(({ method, url }) => method !== "GET" && !url.endsWith("/api/auth/csrf"));
}

describe("ownership offers API", () => {
    afterEach(() => {
        vi.resetModules();
        vi.unstubAllGlobals();
        vi.clearAllMocks();
    });

    it("returns an ownership offer, not a member, when a workspace owner promotion needs consent", async () => {
        stubBackend((url, method) => url.endsWith("/api/workspaces/5/members/11") && method === "PATCH"
            ? json({ outcome: "ownership_offer", offer: OFFER, currentMember: MEMBER }, 202)
            : null);
        const { isOwnershipOfferCreated, updateMemberRole } = await import("@/app/lib/api");

        const result = await updateMemberRole(5, 11, "owner");

        expect(isOwnershipOfferCreated(result)).toBe(true);
        expect(result).toEqual({ outcome: "ownership_offer", offer: OFFER, currentMember: MEMBER });
    });

    it("still returns the changed member for every other workspace role change", async () => {
        stubBackend((url, method) => url.endsWith("/api/workspaces/5/members/11") && method === "PATCH"
            ? json({ ...MEMBER, role: "admin", builtInRole: "admin" })
            : null);
        const { isOwnershipOfferCreated, updateMemberRole } = await import("@/app/lib/api");

        const result = await updateMemberRole(5, 11, "admin");

        expect(isOwnershipOfferCreated(result)).toBe(false);
        expect(result).toMatchObject({ id: 11, role: "admin" });
    });

    it("distinguishes an organization ownership offer (202) from an ordinary role change (204)", async () => {
        stubBackend((url, method) => {
            if (url.endsWith("/api/orgs/3/members/11") && method === "PUT") return new Response(null, { status: 204 });
            if (url.endsWith("/api/orgs/3/members") && method === "POST") {
                return json({ outcome: "ownership_offer", offer: { ...OFFER, scopeKind: "ORGANIZATION", workspaceId: null }, currentMember: null }, 202);
            }
            return null;
        });
        const { addOrgMemberByEmail, isOwnershipOfferCreated, setOrgMemberRole } = await import("@/app/lib/api");

        const changed = await setOrgMemberRole(3, 11, "admin");
        const offered = await addOrgMemberByEmail(3, "dana@example.com", "owner");

        expect(changed).toBeUndefined();
        expect(isOwnershipOfferCreated(changed)).toBe(false);
        expect(isOwnershipOfferCreated(offered)).toBe(true);
        expect(offered).toMatchObject({ currentMember: null, offer: { scopeKind: "ORGANIZATION", workspaceId: null } });
    });

    it("accepts with an empty body, and with the confinement acknowledgement only when asked", async () => {
        const acceptance = { offer: { ...OFFER, state: "ACCEPTED" }, membership: MEMBER, standing: "SATISFIED", enforcedStanding: "SATISFIED" };
        const requests = stubBackend((url, method) => url.endsWith(`/api/ownership-offers/${OFFER.id}/accept`) && method === "POST"
            ? json(acceptance)
            : null);
        const { acceptOwnershipOffer } = await import("@/app/lib/api");

        await expect(acceptOwnershipOffer(OFFER.id)).resolves.toEqual(acceptance);
        await acceptOwnershipOffer(OFFER.id, true);

        expect(mutations(requests).map(({ url, body }) => [url, body])).toEqual([
            [`/api/ownership-offers/${OFFER.id}/accept`, {}],
            [`/api/ownership-offers/${OFFER.id}/accept`, { acknowledgeConfinement: true }],
        ]);
    });

    it("surfaces the confinement acknowledgement requirement as a typed API error", async () => {
        stubBackend((url, method) => url.endsWith(`/api/ownership-offers/${OFFER.id}/accept`) && method === "POST"
            ? json({ code: "PRIVILEGED_MFA_CONFINEMENT_ACKNOWLEDGEMENT_REQUIRED", message: "Acknowledge", affectedCount: 1 }, 409)
            : null);
        const { ApiError, acceptOwnershipOffer } = await import("@/app/lib/api");

        const failure = await acceptOwnershipOffer(OFFER.id).catch((error: unknown) => error);

        expect(failure).toBeInstanceOf(ApiError);
        expect(failure).toMatchObject({ status: 409, code: "PRIVILEGED_MFA_CONFINEMENT_ACKNOWLEDGEMENT_REQUIRED" });
    });

    it("declines and withdraws offers on the exact routes", async () => {
        const requests = stubBackend((url, method) => {
            if (url.endsWith(`/api/ownership-offers/${OFFER.id}/decline`) && method === "POST") return new Response(null, { status: 204 });
            if (url.endsWith(`/api/workspaces/5/ownership-offers/${OFFER.id}`) && method === "DELETE") return new Response(null, { status: 204 });
            if (url.endsWith(`/api/orgs/3/ownership-offers/${OFFER.id}`) && method === "DELETE") return new Response(null, { status: 204 });
            return null;
        });
        const { declineOwnershipOffer, revokeOrgOwnershipOffer, revokeWorkspaceOwnershipOffer } = await import("@/app/lib/api");

        await declineOwnershipOffer(OFFER.id);
        await revokeWorkspaceOwnershipOffer(5, OFFER.id);
        await revokeOrgOwnershipOffer(3, OFFER.id);

        expect(mutations(requests).map(({ url, method }) => [method, url])).toEqual([
            ["POST", `/api/ownership-offers/${OFFER.id}/decline`],
            ["DELETE", `/api/workspaces/5/ownership-offers/${OFFER.id}`],
            ["DELETE", `/api/orgs/3/ownership-offers/${OFFER.id}`],
        ]);
    });

    it("keeps offer ids lossless decimal strings beyond the safe integer range", async () => {
        stubBackend((url) => url.endsWith("/api/ownership-offers") ? json([OFFER]) : null);
        const { getMyOwnershipOffers } = await import("@/app/lib/api");

        const offers = await getMyOwnershipOffers();

        expect(offers[0].id).toBe("9007199254740993");
    });
});
