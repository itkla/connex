import { afterEach, describe, expect, it, vi } from "vitest";

import { settingsDestination } from "@/app/lib/settingsEntryPoints";

const SECURITY = settingsDestination("personal.security").href;
const ATTESTATION = "PRIVILEGED_MFA_ATTESTATION_REQUIRED";
const ENROLLMENT = "PRIVILEGED_MFA_ENROLLMENT_REQUIRED";
const REQUIREMENTS = "/api/auth/webauthn/register/requirements";

type Recorded = { url: string; method: string };
type Route = (url: string, method: string) => Response | Promise<Response> | null;

function json(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function requirements(standing?: string) {
    return json({
        currentPasswordRequired: false,
        emailConfirmationRequired: false,
        emailConfirmationSatisfied: false,
        ...(standing === undefined ? {} : { standing, approvalGaps: [] }),
    });
}

function stubBrowser(pathname: string, search = "") {
    const location = {
        pathname,
        search,
        replace: vi.fn((target: string) => {
            const url = new URL(target, "https://connex.test");
            location.pathname = url.pathname;
            location.search = url.search;
        }),
        reload: vi.fn(),
    };
    const history = {
        replaceState: vi.fn((_state: unknown, _title: string, target: string) => {
            const url = new URL(target, "https://connex.test");
            location.pathname = url.pathname;
            location.search = url.search;
        }),
    };
    const listeners = new Map<string, (event: unknown) => void>();
    vi.stubGlobal("window", {
        addEventListener: vi.fn((type: string, listener: (event: unknown) => void) => listeners.set(type, listener)),
        crypto: { randomUUID: () => "event-id" },
        localStorage: { setItem: vi.fn(), getItem: vi.fn(() => null), removeItem: vi.fn(), key: vi.fn(() => null), length: 0 },
        sessionStorage: { setItem: vi.fn(), getItem: vi.fn(() => null), removeItem: vi.fn(), key: vi.fn(() => null), length: 0 },
        location,
        history,
    });
    vi.stubGlobal("document", { cookie: "connex_workspace=5; NEXT_LOCALE=en" });
    return { location, history, listeners };
}

function stubBackend(route: Route): Recorded[] {
    const requests: Recorded[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
        const url = input instanceof Request ? input.url : String(input);
        const method = init?.method ?? "GET";
        requests.push({ url, method });
        if (url.endsWith("/api/auth/csrf")) {
            return json({ token: "csrf-token", headerName: "X-XSRF-TOKEN", requestIdentity: "request-identity" });
        }
        return (await route(url, method)) ?? json({ message: "Unexpected request" }, 500);
    }));
    return requests;
}

/** Answers successive standing reads in order; each answer is released when the test says so. */
function standingReads() {
    const answers: Array<{ promise: Promise<Response>; resolve: (response: Response) => void }> = [];
    let served = 0;
    const answer = (index: number) => {
        while (answers.length <= index) {
            let resolve: (response: Response) => void = () => undefined;
            const promise = new Promise<Response>((settle) => {
                resolve = settle;
            });
            answers.push({ promise, resolve });
        }
        return answers[index];
    };
    return {
        next: () => answer(served++).promise,
        release: (index: number, response: Response) => answer(index).resolve(response),
        served: () => served,
    };
}

function refusal(code: string) {
    return json({ code, message: "Confined" }, 403);
}

async function flush() {
    for (let i = 0; i < 10; i += 1) await Promise.resolve();
}

describe("privileged MFA confinement in the browser", () => {
    afterEach(() => {
        vi.resetModules();
        vi.unstubAllGlobals();
        vi.clearAllMocks();
    });

    it("confines on an approval refusal, routes to the approval step and raises later refusals itself", async () => {
        const { location } = stubBrowser("/dashboard");
        const requests = stubBackend((url) => {
            if (url.endsWith("/api/rules")) return refusal(ATTESTATION);
            if (url.endsWith(REQUIREMENTS)) return requirements("ATTESTATION_REQUIRED");
            return null;
        });
        const api = await import("@/app/lib/api");

        await expect(api.getRules()).rejects.toMatchObject({ status: 403, code: ATTESTATION });
        expect(location.replace).toHaveBeenCalledWith(`${SECURITY}?mfa=approve`);
        expect(api.privilegedMfaConfinementReason()).toBe("attestation");

        await expect(api.getRules()).rejects.toMatchObject({ status: 403, code: ATTESTATION });
        await expect(api.getPasskeyRegistrationRequirements()).resolves.toMatchObject({ standing: "ATTESTATION_REQUIRED" });
        expect(requests.filter(({ url }) => url.endsWith("/api/rules"))).toHaveLength(1);
    });

    it("stays on onboarding when refused there, so an account without a workspace is not bounced", async () => {
        const { location } = stubBrowser("/onboarding");
        stubBackend((url) => (url.endsWith("/api/rules") ? refusal(ENROLLMENT) : null));
        const api = await import("@/app/lib/api");

        await expect(api.getRules()).rejects.toMatchObject({ code: ENROLLMENT });

        expect(location.replace).not.toHaveBeenCalled();
        expect(api.privilegedMfaConfinementReason()).toBe("enrollment");
    });

    it("moves an enrollment step to the approval step when the first passkey still needs approval", async () => {
        const { history, location } = stubBrowser(SECURITY, "?mfa=enroll");
        stubBackend((url) => (url.endsWith(REQUIREMENTS) ? requirements("ATTESTATION_REQUIRED") : null));
        const api = await import("@/app/lib/api");
        expect(api.privilegedMfaConfinementReason()).toBe("enrollment");

        await expect(api.settlePrivilegedMfaStanding("ceremony")).resolves.toBe("applied");

        expect(history.replaceState).toHaveBeenCalledWith(null, "", `${SECURITY}?mfa=approve`);
        expect(location.search).toBe("?mfa=approve");
        expect(api.privilegedMfaConfinementReason()).toBe("attestation");
        expect(api.currentPrivilegedMfaRequirements()?.standing).toBe("ATTESTATION_REQUIRED");
        await expect(api.getRules()).rejects.toMatchObject({ code: ATTESTATION });
    });

    it.each(["SATISFIED", "UNPRIVILEGED"])("releases on %s and drops the step from the address", async (standing) => {
        const { location } = stubBrowser(SECURITY, "?mfa=approve");
        const requests = stubBackend((url) => {
            if (url.endsWith(REQUIREMENTS)) return requirements(standing);
            if (url.endsWith("/api/rules")) return json([]);
            return null;
        });
        const api = await import("@/app/lib/api");

        await expect(api.settlePrivilegedMfaStanding("check")).resolves.toBe("applied");

        expect(api.privilegedMfaConfinementReason()).toBeNull();
        expect(location.search).toBe("");
        await expect(api.getRules()).resolves.toEqual([]);
        expect(requests.some(({ url }) => url.endsWith("/api/rules"))).toBe(true);
    });

    it("reads again when a refusal overtakes a read, and lets only the fresh read decide", async () => {
        stubBrowser("/dashboard");
        const reads = standingReads();
        stubBackend((url) => {
            if (url.endsWith(REQUIREMENTS)) return reads.next();
            if (url.endsWith("/api/rules")) return refusal(ATTESTATION);
            return null;
        });
        const api = await import("@/app/lib/api");

        const settled = api.settlePrivilegedMfaStanding("check");
        await flush();
        await expect(api.getRules()).rejects.toMatchObject({ code: ATTESTATION });
        reads.release(0, requirements("SATISFIED"));
        await flush();
        expect(api.privilegedMfaConfinementReason()).toBe("attestation");
        expect(api.currentPrivilegedMfaRequirements()).toBeNull();

        reads.release(1, requirements("ATTESTATION_REQUIRED"));
        await expect(settled).resolves.toBe("applied");
        expect(reads.served()).toBe(2);
        expect(api.privilegedMfaConfinementReason()).toBe("attestation");
        expect(api.currentPrivilegedMfaRequirements()?.standing).toBe("ATTESTATION_REQUIRED");
    });

    it("reads again when an identity change overtakes a read", async () => {
        const { listeners } = stubBrowser(SECURITY, "?mfa=approve");
        const reads = standingReads();
        stubBackend((url) => (url.endsWith(REQUIREMENTS) ? reads.next() : null));
        const api = await import("@/app/lib/api");

        const settled = api.settlePrivilegedMfaStanding("check");
        await flush();
        listeners.get("storage")?.({ key: "connex:client-request-identity", newValue: "invalidate:event" });
        reads.release(0, requirements("SATISFIED"));
        await flush();
        expect(api.privilegedMfaConfinementReason()).toBe("attestation");

        reads.release(1, requirements("ATTESTATION_REQUIRED"));
        await expect(settled).resolves.toBe("applied");
        expect(api.privilegedMfaConfinementReason()).toBe("attestation");
    });

    it("never lets an older check undo the release a newer ceremony decided", async () => {
        stubBrowser(SECURITY, "?mfa=approve");
        const reads = standingReads();
        stubBackend((url) => (url.endsWith(REQUIREMENTS) ? reads.next() : null));
        const api = await import("@/app/lib/api");

        const olderCheck = api.settlePrivilegedMfaStanding("check");
        await flush();
        const ceremony = api.settlePrivilegedMfaStanding("ceremony");
        await flush();
        reads.release(1, requirements("SATISFIED"));
        await expect(ceremony).resolves.toBe("applied");
        expect(api.privilegedMfaConfinementReason()).toBeNull();

        reads.release(0, requirements("ATTESTATION_REQUIRED"));
        await expect(olderCheck).resolves.toBe("superseded");
        expect(api.privilegedMfaConfinementReason()).toBeNull();
        expect(api.currentPrivilegedMfaRequirements()?.standing).toBe("SATISFIED");
    });

    it("gives up and reports failure when identity changes keep overtaking the read", async () => {
        const { listeners } = stubBrowser(SECURITY, "?mfa=approve");
        let served = 0;
        stubBackend((url) => {
            if (!url.endsWith(REQUIREMENTS)) return null;
            served += 1;
            listeners.get("storage")?.({ key: "connex:client-request-identity", newValue: `invalidate:${served}` });
            return requirements("SATISFIED");
        });
        const api = await import("@/app/lib/api");

        await expect(api.settlePrivilegedMfaStanding("check")).resolves.toBe("failed");

        expect(served).toBe(3);
        expect(api.privilegedMfaConfinementReason()).toBe("attestation");
        expect(api.currentPrivilegedMfaRequirements()).toBeNull();
    });

    it("keeps confinement when the standing cannot be read", async () => {
        stubBrowser(SECURITY, "?mfa=approve");
        stubBackend((url) => (url.endsWith(REQUIREMENTS) ? json({ message: "Unavailable" }, 503) : null));
        const api = await import("@/app/lib/api");

        await expect(api.settlePrivilegedMfaStanding("ceremony")).resolves.toBe("failed");

        expect(api.privilegedMfaConfinementReason()).toBe("attestation");
    });

    it.each([
        { label: "an empty body", body: {} },
        { label: "a text flag", body: { currentPasswordRequired: "no", emailConfirmationRequired: false, emailConfirmationSatisfied: false } },
        { label: "a malformed gap", body: {
            currentPasswordRequired: false,
            emailConfirmationRequired: false,
            emailConfirmationSatisfied: false,
            standing: "SATISFIED",
            approvalGaps: [{ credentialId: 7 }],
        } },
    ])("treats $label as a failed read rather than an old backend", async ({ body }) => {
        stubBrowser(SECURITY, "?mfa=enroll");
        stubBackend((url) => (url.endsWith(REQUIREMENTS) ? json(body) : null));
        const api = await import("@/app/lib/api");

        await expect(api.settlePrivilegedMfaStanding("ceremony")).resolves.toBe("failed");

        expect(api.privilegedMfaConfinementReason()).toBe("enrollment");
        expect(api.currentPrivilegedMfaRequirements()).toBeNull();
    });

    it("treats a standing this client does not know as a failed read", async () => {
        stubBrowser(SECURITY, "?mfa=enroll");
        stubBackend((url) => (url.endsWith(REQUIREMENTS) ? requirements("PENDING_REVIEW") : null));
        const api = await import("@/app/lib/api");

        await expect(api.settlePrivilegedMfaStanding("ceremony")).resolves.toBe("failed");

        expect(api.privilegedMfaConfinementReason()).toBe("enrollment");
        expect(api.currentPrivilegedMfaRequirements()).toBeNull();
    });

    it("reads the standing again after a confined account leaves a workspace", async () => {
        stubBrowser(SECURITY, "?mfa=approve");
        const requests = stubBackend((url, method) => {
            if (method === "POST" && url.endsWith("/api/workspaces/5/leave")) return json({ activeWorkspaceId: 6 });
            if (url.endsWith(REQUIREMENTS)) return requirements("UNPRIVILEGED");
            return null;
        });
        const api = await import("@/app/lib/api");
        expect(api.privilegedMfaConfinementDestination()).toBe(`${SECURITY}?mfa=approve`);

        await expect(api.leaveWorkspace(5)).resolves.toMatchObject({ activeWorkspaceId: 6 });

        expect(requests.some(({ url }) => url.endsWith(REQUIREMENTS))).toBe(true);
        expect(api.privilegedMfaConfinementReason()).toBeNull();
        expect(api.privilegedMfaConfinementDestination()).toBeNull();
    });

    it("keeps a still-privileged account confined after leaving an organization", async () => {
        stubBrowser(SECURITY, "?mfa=approve");
        stubBackend((url, method) => {
            if (method === "POST" && url.endsWith("/api/orgs/3/leave")) return new Response(null, { status: 204 });
            if (url.endsWith(REQUIREMENTS)) return requirements("ATTESTATION_REQUIRED");
            return null;
        });
        const api = await import("@/app/lib/api");

        await api.leaveOrganization(3);

        expect(api.privilegedMfaConfinementReason()).toBe("attestation");
        expect(api.privilegedMfaConfinementDestination()).toBe(`${SECURITY}?mfa=approve`);
    });

    it("leaves a workspace without reading the standing when the browser is not confined", async () => {
        stubBrowser("/settings/personal/workspaces");
        const requests = stubBackend((url, method) => {
            if (method === "POST" && url.endsWith("/api/workspaces/5/leave")) return json({ activeWorkspaceId: 6 });
            return null;
        });
        const api = await import("@/app/lib/api");

        await api.leaveWorkspace(5);

        expect(requests.some(({ url }) => url.endsWith(REQUIREMENTS))).toBe(false);
    });

    it("forgets the confinement when the account signs out, so signing in again is not refused locally", async () => {
        stubBrowser("/onboarding");
        const requests = stubBackend((url) => {
            if (url.endsWith("/api/rules")) return refusal(ATTESTATION);
            if (url.endsWith("/api/auth/logout")) return new Response(null, { status: 204 });
            if (url.endsWith("/api/auth/login")) return json({ message: "Signed in" });
            return null;
        });
        const api = await import("@/app/lib/api");
        await expect(api.getRules()).rejects.toMatchObject({ code: ATTESTATION });
        expect(api.privilegedMfaConfinementReason()).toBe("attestation");

        await api.logout();

        expect(api.privilegedMfaConfinementReason()).toBeNull();
        expect(api.currentPrivilegedMfaRequirements()).toBeNull();
        await api.login({ username: "dana", password: "correct horse" });
        expect(requests.some(({ url }) => url.endsWith("/api/auth/login"))).toBe(true);
    });

    describe("against a backend that reports no standing", () => {
        it("releases after an accepted enrollment", async () => {
            stubBrowser(SECURITY, "?mfa=enroll");
            stubBackend((url) => (url.endsWith(REQUIREMENTS) ? requirements() : null));
            const api = await import("@/app/lib/api");

            await api.settlePrivilegedMfaStanding("ceremony");

            expect(api.privilegedMfaConfinementReason()).toBeNull();
        });

        it("still releases when a later check decides before the enrollment's own slow read", async () => {
            stubBrowser(SECURITY, "?mfa=enroll");
            const reads = standingReads();
            stubBackend((url) => (url.endsWith(REQUIREMENTS) ? reads.next() : null));
            const api = await import("@/app/lib/api");

            const ceremony = api.settlePrivilegedMfaStanding("ceremony");
            await flush();
            const check = api.settlePrivilegedMfaStanding("check");
            await flush();
            reads.release(1, requirements());
            await expect(check).resolves.toBe("applied");
            expect(api.privilegedMfaConfinementReason()).toBeNull();

            reads.release(0, requirements());
            await expect(ceremony).resolves.toBe("superseded");
            expect(api.privilegedMfaConfinementReason()).toBeNull();
        });

        it("does not release for an enrollment once a refusal has intervened", async () => {
            stubBrowser("/dashboard");
            const reads = standingReads();
            stubBackend((url) => {
                if (url.endsWith(REQUIREMENTS)) return reads.next();
                if (url.endsWith("/api/rules")) return refusal(ENROLLMENT);
                return null;
            });
            const api = await import("@/app/lib/api");

            const ceremony = api.settlePrivilegedMfaStanding("ceremony");
            await flush();
            await expect(api.getRules()).rejects.toMatchObject({ code: ENROLLMENT });
            reads.release(0, requirements());
            reads.release(1, requirements());

            await expect(ceremony).resolves.toBe("applied");
            expect(api.privilegedMfaConfinementReason()).toBe("enrollment");
        });

        it("never releases after a safe exit or a plain check", async () => {
            stubBrowser(SECURITY, "?mfa=enroll");
            stubBackend((url) => (url.endsWith(REQUIREMENTS) ? requirements() : null));
            const api = await import("@/app/lib/api");

            await api.settlePrivilegedMfaStanding("exit");
            await api.settlePrivilegedMfaStanding("check");

            expect(api.privilegedMfaConfinementReason()).toBe("enrollment");
        });

        it("never releases on the approval step", async () => {
            stubBrowser(SECURITY, "?mfa=approve");
            stubBackend((url) => (url.endsWith(REQUIREMENTS) ? requirements() : null));
            const api = await import("@/app/lib/api");

            await api.settlePrivilegedMfaStanding("ceremony");

            expect(api.privilegedMfaConfinementReason()).toBe("attestation");
        });

        it("never releases once passkey approval has been required in this page", async () => {
            stubBrowser("/dashboard");
            stubBackend((url) => {
                if (url.endsWith("/api/rules")) return refusal(ATTESTATION);
                if (url.endsWith(REQUIREMENTS)) return requirements();
                return null;
            });
            const api = await import("@/app/lib/api");
            await expect(api.getRules()).rejects.toMatchObject({ code: ATTESTATION });

            await api.settlePrivilegedMfaStanding("ceremony");

            expect(api.privilegedMfaConfinementReason()).toBe("attestation");
        });
    });
});
