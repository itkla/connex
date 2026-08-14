import { NextRequest } from "next/server";
import { unstable_doesMiddlewareMatch } from "next/experimental/testing/server";
import { getPathMatch } from "next/dist/shared/lib/router/utils/path-match";
import { describe, expect, it } from "vitest";

import nextConfig from "@/next.config";
import { config as proxyConfig, proxy } from "@/proxy";

const FRONTEND_CONTENT_SECURITY_POLICY = "frame-ancestors 'none'";
const ATTACHMENT_CONTENT_SECURITY_POLICY =
    "default-src 'none'; sandbox; frame-ancestors 'none'";

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

function expectFrontendSecurityHeaders(headers: Map<string, string>): void {
    expect(headers.get("x-content-type-options")).toBe("nosniff");
    expect(headers.get("referrer-policy")).toBe("strict-origin-when-cross-origin");
    expect(headers.get("x-frame-options")).toBe("DENY");
    expect(headers.get("content-security-policy")).toBe(FRONTEND_CONTENT_SECURITY_POLICY);
}

describe("frontend security headers", () => {
    it.each([
        ["a pre-authentication HTML path", "/auth/login"],
        ["an authenticated application path", "/dashboard"],
        ["a Next.js static asset", "/_next/static/chunks/app.js"],
    ])("protects %s", async (_description, path) => {
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

    it.each([
        ["an API rewrite", "/api/auth/csrf", "http://localhost:8080/api/auth/csrf"],
        ["a SAML rewrite", "/saml2/authenticate/example", "http://localhost:8080/saml2/authenticate/example"],
    ])("protects %s when the backend is unavailable", (_description, path, destination) => {
        expect(unstable_doesMiddlewareMatch({ config: proxyConfig, nextConfig, url: path })).toBe(true);

        const response = proxy(new NextRequest(`http://localhost:3000${path}`));

        expectFrontendSecurityHeaders(new Map(response.headers.entries()));
        expect(response.headers.get("x-middleware-rewrite")).toBe(destination);
    });
});
