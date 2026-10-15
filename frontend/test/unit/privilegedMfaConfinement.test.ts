import { afterEach, describe, expect, it, vi } from "vitest";

import { settingsDestination } from "@/app/lib/settingsEntryPoints";

const SECURITY = settingsDestination("personal.security").href;
const ATTESTATION = "PRIVILEGED_MFA_ATTESTATION_REQUIRED";
const ENROLLMENT = "PRIVILEGED_MFA_ENROLLMENT_REQUIRED";

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
        localStorage: { setItem: vi.fn() },
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

function refusal(code: string) {
    return json({ code, message: "Confined" }, 403);
}

function deferred<T>() {
    let resolve: (value: T) => void = () => {};
    const promise = new Promise<T>((settle) => {
        resolve = settle;
    });
    return { promise, resolve };
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
            if (url.endsWith("/api/auth/webauthn/register/requirements")) return requirements("ATTESTATION_REQUIRED");
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
        stubBackend((url) => (url.endsWith("/register/requirements") ? requirements("ATTESTATION_REQUIRED") : null));
        const api = await import("@/app/lib/api");
        expect(api.privilegedMfaConfinementReason()).toBe("enrollment");

        await api.settlePrivilegedMfaStanding("ceremony");

        expect(history.replaceState).toHaveBeenCalledWith(null, "", `${SECURITY}?mfa=approve`);
        expect(location.search).toBe("?mfa=approve");
        expect(api.privilegedMfaConfinementReason()).toBe("attestation");
        await expect(api.getRules()).rejects.toMatchObject({ code: ATTESTATION });
    });

    it.each(["SATISFIED", "UNPRIVILEGED"])("releases on %s and drops the step from the address", async (standing) => {
        const { location } = stubBrowser(SECURITY, "?mfa=approve");
        const requests = stubBackend((url) => {
            if (url.endsWith("/register/requirements")) return requirements(standing);
            if (url.endsWith("/api/rules")) return json([]);
            return null;
        });
        const api = await import("@/app/lib/api");

        await api.settlePrivilegedMfaStanding("check");

        expect(api.privilegedMfaConfinementReason()).toBeNull();
        expect(location.search).toBe("");
        await expect(api.getRules()).resolves.toEqual([]);
        expect(requests.some(({ url }) => url.endsWith("/api/rules"))).toBe(true);
    });

    it("ignores a release that a newer refusal overtook", async () => {
        stubBrowser("/dashboard");
        const pending = deferred<Response>();
        stubBackend((url) => {
            if (url.endsWith("/register/requirements")) return pending.promise;
            if (url.endsWith("/api/rules")) return refusal(ATTESTATION);
            return null;
        });
        const api = await import("@/app/lib/api");

        const settled = api.settlePrivilegedMfaStanding("check");
        await expect(api.getRules()).rejects.toMatchObject({ code: ATTESTATION });
        pending.resolve(requirements("SATISFIED"));

        await expect(settled).resolves.toMatchObject({ standing: "SATISFIED" });
        expect(api.privilegedMfaConfinementReason()).toBe("attestation");
    });

    it("ignores a release that an identity change overtook", async () => {
        const { listeners } = stubBrowser(SECURITY, "?mfa=approve");
        const pending = deferred<Response>();
        stubBackend((url) => (url.endsWith("/register/requirements") ? pending.promise : null));
        const api = await import("@/app/lib/api");

        const settled = api.settlePrivilegedMfaStanding("check");
        listeners.get("storage")?.({ key: "connex:client-request-identity", newValue: "invalidate:event" });
        pending.resolve(requirements("SATISFIED"));
        await settled;

        expect(api.privilegedMfaConfinementReason()).toBe("attestation");
    });

    it("keeps confinement when the standing cannot be read", async () => {
        stubBrowser(SECURITY, "?mfa=approve");
        stubBackend((url) => (url.endsWith("/register/requirements") ? json({ message: "Unavailable" }, 503) : null));
        const api = await import("@/app/lib/api");

        await expect(api.settlePrivilegedMfaStanding("ceremony")).resolves.toBeNull();

        expect(api.privilegedMfaConfinementReason()).toBe("attestation");
    });

    it("keeps confinement on a standing this client does not know", async () => {
        stubBrowser(SECURITY, "?mfa=enroll");
        stubBackend((url) => (url.endsWith("/register/requirements") ? requirements("SOMETHING_NEW") : null));
        const api = await import("@/app/lib/api");

        await api.settlePrivilegedMfaStanding("ceremony");

        expect(api.privilegedMfaConfinementReason()).toBe("enrollment");
    });

    describe("against a backend that reports no standing", () => {
        it("releases after an accepted enrollment", async () => {
            stubBrowser(SECURITY, "?mfa=enroll");
            stubBackend((url) => (url.endsWith("/register/requirements") ? requirements() : null));
            const api = await import("@/app/lib/api");

            await api.settlePrivilegedMfaStanding("ceremony");

            expect(api.privilegedMfaConfinementReason()).toBeNull();
        });

        it("never releases after a safe exit or a plain check", async () => {
            stubBrowser(SECURITY, "?mfa=enroll");
            stubBackend((url) => (url.endsWith("/register/requirements") ? requirements() : null));
            const api = await import("@/app/lib/api");

            await api.settlePrivilegedMfaStanding("exit");
            await api.settlePrivilegedMfaStanding("check");

            expect(api.privilegedMfaConfinementReason()).toBe("enrollment");
        });

        it("never releases on the approval step", async () => {
            stubBrowser(SECURITY, "?mfa=approve");
            stubBackend((url) => (url.endsWith("/register/requirements") ? requirements() : null));
            const api = await import("@/app/lib/api");

            await api.settlePrivilegedMfaStanding("ceremony");

            expect(api.privilegedMfaConfinementReason()).toBe("attestation");
        });

        it("never releases once passkey approval has been required in this page", async () => {
            stubBrowser("/dashboard");
            stubBackend((url) => {
                if (url.endsWith("/api/rules")) return refusal(ATTESTATION);
                if (url.endsWith("/register/requirements")) return requirements();
                return null;
            });
            const api = await import("@/app/lib/api");
            await expect(api.getRules()).rejects.toMatchObject({ code: ATTESTATION });

            await api.settlePrivilegedMfaStanding("ceremony");

            expect(api.privilegedMfaConfinementReason()).toBe("attestation");
        });
    });
});
