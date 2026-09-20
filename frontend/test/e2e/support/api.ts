import type { APIRequestContext } from "@playwright/test";
import { expect } from "@playwright/test";

/** A record created by the setup project, addressable by both id and visible name. */
export type SeededRecord = { id: number; name: string };

/** Credentials and identifiers for the tenant provisioned for one e2e run. */
export type RunFixture = {
    username: string;
    password: string;
    email: string;
    workspaceId: number;
    contacts: {
        peek: SeededRecord;
        edit: SeededRecord;
        activity: SeededRecord;
        search: SeededRecord;
        archive: SeededRecord;
        watch: SeededRecord;
        manualDuplicate: SeededRecord;
        ambiguityPrimary: SeededRecord;
        ambiguitySecondary: SeededRecord;
        ambiguityPrimaryJa: SeededRecord;
        ambiguitySecondaryJa: SeededRecord;
    };
    companies: {
        primary: SeededRecord;
        archive: SeededRecord;
    };
    deals: {
        primary: SeededRecord;
    };
    activities: {
        evidence: SeededRecord;
    };
    companyName: string;
    ambiguityEmail: string;
    ambiguityEmailJa: string;
};

type CsrfBootstrap = { token: string; headerName: string };

/**
 * Registers a throwaway user against the dev backend. Under the dev profile the register
 * endpoint also logs the session in, creates a default workspace, and sets the
 * JSESSIONID + connex_workspace cookies on the response — one call yields a full tenant.
 */
export async function registerUser(
    api: APIRequestContext,
    credentials: { username: string; password: string; email: string },
): Promise<void> {
    const response = await api.post("/api/auth/register", {
        timeout: 120_000,
        data: {
            username: credentials.username,
            password: credentials.password,
            displayName: "E2E Harness",
            email: credentials.email,
            timezone: "UTC",
        },
    });
    expect(response.status(), await safeBody(response)).toBe(200);
}

/** Reads the session's active workspace id, the tenant every seeded record belongs to. */
export async function activeWorkspaceId(api: APIRequestContext): Promise<number> {
    const response = await api.get("/api/workspaces", { timeout: 120_000 });
    expect(response.status()).toBe(200);
    const body = (await response.json()) as { activeWorkspaceId: number };
    expect(body.activeWorkspaceId).toBeGreaterThan(0);
    return body.activeWorkspaceId;
}

/** Fetches the CSRF token scoped writes require (register/login are exempt; seeding is not). */
export async function csrfBootstrap(api: APIRequestContext): Promise<CsrfBootstrap> {
    const response = await api.get("/api/auth/csrf", { timeout: 120_000 });
    expect(response.status()).toBe(200);
    const body = (await response.json()) as CsrfBootstrap;
    expect(body.token).toBeTruthy();
    expect(body.headerName).toBeTruthy();
    return body;
}

/**
 * Points one organization at the scripted AI provider, over the same controller an operator uses.
 *
 * Only ever call this for a tenant the calling spec registered itself. Provider readiness is per
 * organization, which is the whole reason the scripted profile can be on for the shared e2e stack
 * without making AI usable for the project storage-state tenants — and several specs assert exactly
 * that honest refusal. Configuring a shared tenant here would delete their premise.
 *
 * The endpoint is a loopback literal that is never dialed: the scripted adapter answers from
 * fixtures. It still has to pass the save path's address resolution, which is why the backend needs
 * `CONNEX_AI_ALLOW_INTERNAL_ENDPOINTS`.
 *
 * The save also demands a fresh passkey step-up on the calling session: the service calls
 * `requireRecentAuthentication` unconditionally, and only a completed WebAuthn ceremony marks that
 * proof. The caller must therefore enrol a passkey first — a password session never satisfies it,
 * whatever the privileged-MFA posture says.
 *
 * @param api request context carrying the registered tenant's session
 * @param workspaceId the workspace whose organization is configured
 * @param csrf bootstrap token for the same session
 * @param modelId a scripted capability class, e.g. `scripted-native-stream`
 */
export async function configureScriptedAiProvider(
    api: APIRequestContext,
    workspaceId: number,
    csrf: CsrfBootstrap,
    modelId: string,
): Promise<void> {
    const response = await api.put(`/api/ai/provider?workspaceId=${workspaceId}`, {
        timeout: 120_000,
        headers: {
            "X-Workspace-Id": String(workspaceId),
            [csrf.headerName]: csrf.token,
        },
        data: {
            provider: "openai_compatible",
            modelId,
            endpoint: "http://127.0.0.1:8080/v1",
            allowInternalEndpoint: true,
            noTrainingAttested: true,
            enabled: true,
        },
    });
    expect(
        response.status(),
        `PUT /api/ai/provider returned ${response.status()}: ${await safeBody(response)}. `
        + "A 400 means the backend is missing CONNEX_AI_ALLOW_INTERNAL_ENDPOINTS=true; a 403 with "
        + "RECENT_AUTHENTICATION_REQUIRED means the session has no fresh passkey step-up, or "
        + "CONNEX_RECENT_AUTHENTICATION_WINDOW no longer matches what the e2e job sets. Neither is "
        + "a product defect.",
    ).toBe(200);
}

/** A seeding client bound to one workspace and CSRF token. */
export type Seeder = {
    post: (path: string, data: Record<string, unknown>) => Promise<Record<string, unknown>>;
};

/** Builds a write client that stamps every request with the tenant header and CSRF token. */
export function seeder(api: APIRequestContext, workspaceId: number, csrf: CsrfBootstrap): Seeder {
    return {
        async post(path, data) {
            const response = await api.post(path, {
                timeout: 120_000,
                headers: {
                    "X-Workspace-Id": String(workspaceId),
                    [csrf.headerName]: csrf.token,
                },
                data,
            });
            expect(response.status(), `POST ${path}: ${await safeBody(response)}`).toBeLessThan(300);
            return (await response.json()) as Record<string, unknown>;
        },
    };
}

async function safeBody(response: { text: () => Promise<string> }): Promise<string> {
    try {
        return (await response.text()).slice(0, 500);
    } catch {
        return "<unreadable body>";
    }
}
