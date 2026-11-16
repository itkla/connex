import type { AuthenticationResponseJSON } from "@simplewebauthn/browser";
import { afterEach, describe, expect, it, vi } from "vitest";

import { AUDIT_EXPORT_SELECTION_LIMIT } from "@/app/lib/auditExport";
import { workspacePinnedRequest } from "@/app/lib/renderedWorkspace";

const authenticate = vi.hoisted(() => vi.fn(async (): Promise<AuthenticationResponseJSON> => ({
    id: "credential", rawId: "credential", type: "public-key", clientExtensionResults: {},
    response: { authenticatorData: "data", clientDataJSON: "data", signature: "signature" },
})));
vi.mock("@simplewebauthn/browser", () => ({ startAuthentication: authenticate }));

type RequestCall = { url: URL; headers: Headers };

function json(value: unknown, status = 200): Response {
    return new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json" } });
}

function browser(handler?: (call: RequestCall) => Response) {
    const calls: RequestCall[] = [];
    const click = vi.fn();
    const anchor = { href: "", download: "", click, remove: vi.fn() };
    vi.stubGlobal("window", {
        location: { pathname: "/settings/workspace/audit-diagnostics", search: "" },
        addEventListener: vi.fn(),
        localStorage: { getItem: vi.fn(), setItem: vi.fn() },
    });
    vi.stubGlobal("document", {
        cookie: "connex_workspace=9; NEXT_LOCALE=ja",
        createElement: () => anchor,
        body: { appendChild: vi.fn() },
    });
    vi.stubGlobal("URL", class extends URL {
        static createObjectURL() { return "blob:audit"; }
        static revokeObjectURL() { return undefined; }
    });
    vi.stubGlobal("fetch", vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
        const call = { url: new URL(String(input), "https://connex.test"), headers: new Headers(init?.headers) };
        calls.push(call);
        if (call.url.pathname === "/api/auth/csrf") return json({ token: "csrf", headerName: "X-XSRF-TOKEN" });
        return handler?.(call) ?? new Response("id\r\n3\r\n", { headers: { "Content-Type": "text/csv" } });
    }));
    return { calls, click, anchor };
}

afterEach(() => {
    vi.resetModules();
    vi.unstubAllGlobals();
    vi.clearAllMocks();
});

describe("bounded audit download requests", () => {
    it.each([undefined, 3])("exports only selected IDs with explicit bounds for org %s", async (orgId) => {
        const { calls, click, anchor } = browser();
        const { exportAuditRowsCsv } = await import("@/app/lib/api");
        await exportAuditRowsCsv([42, 7], orgId, workspacePinnedRequest(5));
        expect(calls).toHaveLength(1);
        expect(calls[0].url.pathname).toBe(orgId === undefined ? "/api/audit/export" : "/api/orgs/3/audit/export");
        expect(Object.fromEntries(calls[0].url.searchParams)).toEqual({ ids: "42,7", limit: "200", offset: "0" });
        expect(calls[0].headers.get("X-Workspace-Id")).toBe("5");
        expect(calls[0].headers.get("Accept-Language")).toBe("ja");
        expect(click).toHaveBeenCalledOnce();
        expect(anchor.download).toBe(orgId === undefined ? "audit-log.csv" : "org-audit-log.csv");
    });

    it.each([[], [0], [-1], [1.5], [Number.NaN], [2_147_483_648], Array.from({ length: AUDIT_EXPORT_SELECTION_LIMIT + 1 }, (_, i) => i + 1)].map((ids) => ({ ids })))(
        "refuses an empty, invalid, or oversized selection without fetching", async ({ ids }) => {
            const { calls } = browser();
            const { exportAuditRowsCsv } = await import("@/app/lib/api");
            expect(() => exportAuditRowsCsv(ids)).toThrow("Invalid audit export selection");
            expect(calls).toHaveLength(0);
        },
    );

    it("retries the same pinned selection after the existing passkey step-up", async () => {
        let attempts = 0;
        const { calls, click } = browser(({ url }) => {
            if (url.pathname.endsWith("/options")) return json({ challenge: "challenge" });
            if (url.pathname === "/api/auth/webauthn/step-up") return json({ user: { id: 7 } });
            attempts += 1;
            document.cookie = "connex_workspace=10; NEXT_LOCALE=ja";
            return attempts === 1
                ? json({ code: "RECENT_AUTHENTICATION_REQUIRED", message: "Recent authentication required" }, 403)
                : new Response("id\r\n42\r\n");
        });
        const { exportAuditRowsCsv } = await import("@/app/lib/api");
        await exportAuditRowsCsv([42], undefined, workspacePinnedRequest(5));
        const exports = calls.filter(({ url }) => url.pathname === "/api/audit/export");
        expect(exports).toHaveLength(2);
        expect(exports.map(({ headers }) => headers.get("X-Workspace-Id"))).toEqual(["5", "5"]);
        expect(exports.map(({ url }) => url.searchParams.get("ids"))).toEqual(["42", "42"]);
        expect(authenticate).toHaveBeenCalledOnce();
        expect(click).toHaveBeenCalledOnce();
    });

    it("never downloads a refused export response", async () => {
        const { click } = browser(() => json({ message: "Refused" }, 403));
        const { exportAuditRowsCsv } = await import("@/app/lib/api");
        await expect(exportAuditRowsCsv([42])).rejects.toMatchObject({ status: 403 });
        expect(click).not.toHaveBeenCalled();
    });
});
