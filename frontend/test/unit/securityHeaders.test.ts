import { unstable_doesMiddlewareMatch } from "next/experimental/testing/server";
import { getPathMatch } from "next/dist/shared/lib/router/utils/path-match";
import { NextRequest } from "next/server";
import { afterEach, describe, expect, it, vi } from "vitest";

import nextConfig from "@/next.config";
import { isProtectedPath } from "@/app/lib/protectedRoutes";
import { config as proxyConfig, proxy } from "@/proxy";
import {
    createFrontendContentSecurityPolicy,
    FRONTEND_CONTENT_SECURITY_POLICY,
    resolveContentSecurityPolicyMode,
} from "@/security-headers";

const ATTACHMENT_CONTENT_SECURITY_POLICY =
    "default-src 'none'; sandbox; frame-ancestors 'none'";
const REQUIRED_DIRECTIVES = [
    "default-src",
    "script-src",
    "style-src",
    "img-src",
    "font-src",
    "connect-src",
    "object-src",
    "base-uri",
    "form-action",
    "frame-ancestors",
];

async function headersForPath(path: string): Promise<Map<string, string>> {
    if (!nextConfig.headers) {
        throw new Error("next.config.ts must declare headers()");
    }

    const resolved = new Map<string, string>();
    for (const route of await nextConfig.headers()) {
        if (getPathMatch(route.source)(path) === false) {
            continue;
        }
        for (const header of route.headers) {
            resolved.set(header.key.toLowerCase(), header.value);
        }
    }
    return resolved;
}

function expectFrontendSecurityHeaders(
    headers: Map<string, string>,
    referrerPolicy = "strict-origin-when-cross-origin",
): void {
    expect(headers.get("x-content-type-options")).toBe("nosniff");
    expect(headers.get("referrer-policy")).toBe(referrerPolicy);
    expect(headers.get("x-frame-options")).toBe("DENY");
    expect(headers.get("content-security-policy")).toBe(FRONTEND_CONTENT_SECURITY_POLICY);
}

function nonceFromPolicy(policy: string): string {
    const nonce = /'nonce-([^']+)'/.exec(policy)?.[1];
    if (!nonce) throw new Error("policy must contain a nonce source");
    return nonce;
}

function reportOnlyPolicy(path = "/auth/login"): { response: Response; policy: string } {
    vi.stubEnv("CONNEX_CSP_MODE", "report-only");
    vi.stubEnv("CONNEX_CSP_IMAGE_ORIGINS", "");
    vi.stubEnv("NEXT_PUBLIC_WS_URL", "");
    const response = proxy(new NextRequest(`http://localhost:3000${path}`));
    const policy = response.headers.get("content-security-policy-report-only");
    if (!policy) throw new Error("proxy must emit a Report-Only policy");
    return { response, policy };
}

afterEach(() => {
    vi.unstubAllEnvs();
});

describe("frontend security headers", () => {
    it.each([
        ["a pre-authentication HTML path", "/auth/login"],
        ["an authenticated application path", "/dashboard"],
        ["a Next.js static asset", "/_next/static/chunks/app.js"],
    ])("protects %s with the shared baseline", async (_description, path) => {
        expectFrontendSecurityHeaders(await headersForPath(path));
    });

    it("keeps the stricter attachment policy after the catch-all policy", async () => {
        const headers = await headersForPath("/attachments/x");

        expect(headers.get("x-content-type-options")).toBe("nosniff");
        expect(headers.get("referrer-policy")).toBe("strict-origin-when-cross-origin");
        expect(headers.get("x-frame-options")).toBe("DENY");
        expect(headers.get("content-disposition")).toBe("attachment");
        expect(headers.get("content-security-policy")).toBe(ATTACHMENT_CONTENT_SECURITY_POLICY);
    });

    it("sets no-referrer on document-acceptance HTML and nowhere else", async () => {
        expectFrontendSecurityHeaders(
            await headersForPath(`/document-acceptance/w12-${"a".repeat(64)}`),
            "no-referrer",
        );
        expectFrontendSecurityHeaders(await headersForPath("/records/deals/42"));
    });

    it("keeps document acceptance public while applying the runtime no-referrer override", () => {
        const path = `/document-acceptance/w12-${"a".repeat(64)}`;
        const { response } = reportOnlyPolicy(path);

        expect(isProtectedPath(path)).toBe(false);
        expect(response.status).toBe(200);
        expect(response.headers.get("location")).toBeNull();
        expect(response.headers.get("referrer-policy")).toBe("no-referrer");
        expect(reportOnlyPolicy("/auth/login").response.headers.get("referrer-policy"))
            .toBe("strict-origin-when-cross-origin");
    });

    it("strips acceptance credentials while preserving protected-route request headers", () => {
        vi.stubEnv("CONNEX_CSP_MODE", "report-only");
        const cookie = "JSESSIONID=session-secret; connex_workspace=42; preference=kept";
        const requestHeaders = {
            authorization: "Bearer incoming-secret",
            cookie,
            "proxy-authorization": "Basic proxy-secret",
            "x-csrf-token": "csrf-secret",
            "x-workspace-id": "42",
        };
        const acceptance = proxy(new NextRequest(
            `http://localhost:3000/document-acceptance/w12-${"a".repeat(64)}`,
            { headers: requestHeaders },
        ));

        expect(acceptance.status).toBe(200);
        for (const header of [
            "authorization",
            "cookie",
            "proxy-authorization",
            "x-csrf-token",
            "x-workspace-id",
        ]) {
            expect(acceptance.headers.get(`x-middleware-request-${header}`)).toBeNull();
            expect(acceptance.headers.get("x-middleware-override-headers"))
                .not.toContain(header);
        }

        const protectedRoute = proxy(new NextRequest("http://localhost:3000/dashboard", {
            headers: requestHeaders,
        }));
        expect(protectedRoute.status).toBe(200);
        expect(protectedRoute.headers.get("x-middleware-request-cookie")).toBe(cookie);
        expect(protectedRoute.headers.get("x-middleware-request-authorization"))
            .toBe("Bearer incoming-secret");
        expect(protectedRoute.headers.get("x-middleware-request-x-workspace-id")).toBe("42");
    });

    it("resolves every required directive without production unsafe-eval", () => {
        const policy = createFrontendContentSecurityPolicy({
            nonce: "production-nonce",
            requestUrl: "https://connex.example.com/dashboard",
            isDevelopment: false,
        });

        for (const directive of REQUIRED_DIRECTIVES) {
            expect(policy).toMatch(new RegExp(`(?:^|; )${directive} `));
        }
        expect(policy).toContain("script-src 'self' 'nonce-production-nonce' 'strict-dynamic'");
        expect(policy).toContain("object-src 'none'");
        expect(policy).toContain("base-uri 'none'");
        expect(policy).toContain("frame-src 'none'");
        expect(policy).toContain("worker-src 'none'");
        expect(policy).toContain(
            "connect-src 'self' ws://connex.example.com wss://connex.example.com",
        );
        expect(policy).not.toContain("'unsafe-eval'");
        expect(policy).not.toContain("*");
    });

    it("allows development eval and only exact WebSocket origins", () => {
        const policy = createFrontendContentSecurityPolicy({
            nonce: "development-nonce",
            requestUrl: "http://localhost:3000/dashboard",
            isDevelopment: true,
            configuredWebSocketUrl: "wss://realtime.example.com/api/ws",
        });

        expect(policy).toContain("'unsafe-eval'");
        expect(policy).toContain(
            "connect-src 'self' ws://localhost:3000 wss://localhost:3000 wss://realtime.example.com",
        );
        expect(policy).not.toMatch(/connect-src[^;]*(?:^|\s)(?:ws:|wss:)(?:\s|;|$)/);
    });

    it("accepts only explicit HTTPS origins from the image allowlist", () => {
        const policy = createFrontendContentSecurityPolicy({
            nonce: "image-nonce",
            requestUrl: "https://connex.example.com/dashboard",
            isDevelopment: false,
            configuredImageOrigins: [
                "https://images-b.example",
                "https://images-a.example/",
                "https://images-a.example",
                "https://images-b.example/path",
                "http://images-c.example",
                "https://*.example.com",
                "https://images.example;script-src",
            ].join(","),
        });

        expect(policy).toContain(
            "img-src 'self' blob: data: https://images-a.example https://images-b.example",
        );
        expect(policy).not.toContain("images-c.example");
        expect(policy).not.toContain("*.example.com");
        expect(policy).not.toContain("images.example;script-src");
    });

    it("rejects CSP delimiters from request and configured WebSocket origins", () => {
        const policy = createFrontendContentSecurityPolicy({
            nonce: "delimiter-nonce",
            requestUrl: "https://connex.example;report-uri/dashboard",
            isDevelopment: false,
            configuredWebSocketUrl: "wss://stream.example;script-src/api/ws",
        });

        expect(policy).toContain("connect-src 'self'");
        expect(policy).not.toContain("ws://connex.example;report-uri");
        expect(policy).not.toContain("wss://stream.example;script-src");
    });

    it("defaults absent and invalid modes to Report-Only", () => {
        expect(resolveContentSecurityPolicyMode(undefined)).toBe("report-only");
        expect(resolveContentSecurityPolicyMode("invalid")).toBe("report-only");
        expect(resolveContentSecurityPolicyMode("enforce")).toBe("enforce");
    });

    it("emits a fresh nonce and propagates the enforcement policy upstream", () => {
        const first = reportOnlyPolicy();
        const second = reportOnlyPolicy();
        const firstNonce = nonceFromPolicy(first.policy);
        const secondNonce = nonceFromPolicy(second.policy);

        expect(firstNonce).not.toBe(secondNonce);
        expect(first.response.headers.has("content-security-policy")).toBe(false);
        expect(first.response.headers.get("x-middleware-request-x-nonce")).toBe(firstNonce);
        expect(first.response.headers.get("x-middleware-request-content-security-policy")).toBe(
            first.policy,
        );
    });

    it("places the full policy in the enforcement header when promoted", () => {
        vi.stubEnv("CONNEX_CSP_MODE", "enforce");
        const response = proxy(new NextRequest("https://connex.example.com/auth/login"));
        const policy = response.headers.get("content-security-policy");

        expect(policy).toContain("default-src 'self'");
        expect(policy).toContain("script-src 'self' 'nonce-");
        expect(response.headers.has("content-security-policy-report-only")).toBe(false);
    });

    it("uses the browser-facing origin for WebSocket connections behind a reverse proxy", () => {
        vi.stubEnv("CONNEX_CSP_MODE", "report-only");
        vi.stubEnv("NEXT_PUBLIC_WS_URL", "");
        const response = proxy(new NextRequest("http://0.0.0.0:3000/auth/login", {
            headers: {
                host: "connex.example.com",
                "x-forwarded-proto": "https",
            },
        }));
        const policy = response.headers.get("content-security-policy-report-only");

        expect(policy).toContain(
            "connect-src 'self' ws://connex.example.com wss://connex.example.com",
        );
        expect(policy).not.toContain("0.0.0.0:3000");
    });

    it("rejects a wildcard browser-facing Host", () => {
        vi.stubEnv("CONNEX_CSP_MODE", "report-only");
        vi.stubEnv("NEXT_PUBLIC_WS_URL", "");
        const response = proxy(new NextRequest("http://0.0.0.0:3000/auth/login", {
            headers: {
                host: "*.example.com",
                "x-forwarded-proto": "https",
            },
        }));
        const policy = response.headers.get("content-security-policy-report-only");

        expect(policy).toContain("connect-src 'self'");
        expect(policy).not.toContain("*");
    });

    it("applies CSP to protected-route redirects", () => {
        const { response, policy } = reportOnlyPolicy("/dashboard");

        expect(response.status).toBe(307);
        expect(response.headers.get("location")).toBe(
            "http://localhost:3000/auth/login?redirect=%2Fdashboard",
        );
        expect(policy).toContain("frame-ancestors 'none'");
    });

    it.each([
        "/",
        "/auth/login",
        "/docs",
        "/dashboard",
        "/records/contacts/1",
        "/apiary",
        "/saml2-guide",
        "/favicon.ico-help",
    ])(
        "matches HTML route %s",
        (path) => {
            expect(unstable_doesMiddlewareMatch({ config: proxyConfig, nextConfig, url: path })).toBe(true);
        },
    );

    it.each([
        ["an API path", "/api/auth/csrf"],
        ["a SAML path", "/saml2/authenticate/example"],
        ["a Next.js static asset", "/_next/static/chunks/app.js"],
        ["the image optimizer", "/_next/image?url=%2Ffile.svg&w=64&q=75"],
    ])("leaves %s outside proxy interception", (_description, path) => {
        expect(unstable_doesMiddlewareMatch({ config: proxyConfig, nextConfig, url: path })).toBe(false);
    });
});
