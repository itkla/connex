import { afterEach, describe, expect, it, vi } from "vitest";

type Recorded = { url: string; method: string; body: unknown };

function json(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: { "Content-Type": "application/json" },
    });
}

function stubBrowser() {
    vi.stubGlobal("window", {
        addEventListener: vi.fn(),
        crypto: { randomUUID: () => "event-id" },
        localStorage: { setItem: vi.fn() },
        location: { pathname: "/settings/workspace/people", search: "" },
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

describe("role changes that confine members", () => {
    afterEach(() => {
        vi.resetModules();
        vi.unstubAllGlobals();
        vi.clearAllMocks();
    });

    it("reads how many members a refused role change would confine", async () => {
        stubBrowser();
        stubBackend((url) => url.endsWith("/api/workspaces/5/members/9")
            ? json({
                code: "PRIVILEGED_MFA_CONFINEMENT_ACKNOWLEDGEMENT_REQUIRED",
                message: "This change leaves members without a passkey approved in this organization",
                affectedCount: 2,
            }, 409)
            : null);
        const api = await import("@/app/lib/api");

        await expect(api.updateMemberRole(5, 9, "admin")).rejects.toMatchObject({
            status: 409,
            code: api.PRIVILEGED_MFA_CONFINEMENT_ACKNOWLEDGEMENT_REQUIRED_CODE,
            affectedCount: 2,
            fieldErrors: undefined,
        });
    });

    it.each([
        { label: "another refusal", body: { code: "WORKSPACE_LAST_OWNER", message: "Last owner", affectedCount: 2 } },
        { label: "a fractional count", body: { code: "PRIVILEGED_MFA_CONFINEMENT_ACKNOWLEDGEMENT_REQUIRED", affectedCount: 1.5 } },
        { label: "a negative count", body: { code: "PRIVILEGED_MFA_CONFINEMENT_ACKNOWLEDGEMENT_REQUIRED", affectedCount: -1 } },
        { label: "a text count", body: { code: "PRIVILEGED_MFA_CONFINEMENT_ACKNOWLEDGEMENT_REQUIRED", affectedCount: "2" } },
    ])("keeps no count for $label", async ({ body }) => {
        stubBrowser();
        stubBackend((url) => url.endsWith("/api/orgs/3/members/9") ? json(body, 409) : null);
        const api = await import("@/app/lib/api");

        const refusal = await api.setOrgMemberRole(3, 9, "admin").catch((err: unknown) => err);

        expect(refusal).toBeInstanceOf(api.ApiError);
        expect((refusal as InstanceType<typeof api.ApiError>).affectedCount).toBeUndefined();
    });

    it("sends the acknowledgement only once the actor has confirmed it", async () => {
        stubBrowser();
        const requests = stubBackend((url, method) => {
            if (method === "PATCH" && url.endsWith("/api/workspaces/5/members/9")) {
                return json({ id: 9, role: "admin", builtInRole: "admin" });
            }
            if (method === "PUT" && url.endsWith("/api/workspaces/5/roles/4")) {
                return json({ id: 4, name: "Ops", permissions: ["MEMBER_MANAGE"] });
            }
            if (url.endsWith("/api/orgs/3/members/9") || url.endsWith("/api/orgs/3/members")) {
                return new Response(null, { status: 204 });
            }
            return null;
        });
        const api = await import("@/app/lib/api");

        for (const acknowledge of [false, true]) {
            await api.updateMemberRole(5, 9, "admin", acknowledge);
            await api.assignMemberCustomRole(5, 9, 4, acknowledge);
            await api.updateWorkspaceRole(5, 4, "Ops", ["MEMBER_MANAGE"], acknowledge);
            await api.setOrgMemberRole(3, 9, "admin", acknowledge);
            await api.addOrgMemberByEmail(3, "dana@example.com", "admin", acknowledge);
        }

        expect(requests.filter(({ method }) => method !== "GET").map(({ body }) => body)).toEqual([
            { role: "admin" },
            { roleId: 4 },
            { name: "Ops", permissions: ["MEMBER_MANAGE"] },
            { orgRole: "admin" },
            { email: "dana@example.com", orgRole: "admin" },
            { role: "admin", acknowledgeConfinement: true },
            { roleId: 4, acknowledgeConfinement: true },
            { name: "Ops", permissions: ["MEMBER_MANAGE"], acknowledgeConfinement: true },
            { orgRole: "admin", acknowledgeConfinement: true },
            { email: "dana@example.com", orgRole: "admin", acknowledgeConfinement: true },
        ]);
    });
});
