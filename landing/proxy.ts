import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

import {
    applyFrontendContentSecurityPolicy,
    applyFrontendReportingEndpoints,
    contentSecurityPolicyReportingEndpoint,
    createFrontendContentSecurityPolicy,
    createReportingEndpointsHeader,
    resolveContentSecurityPolicyMode,
} from './security-headers';

function createNonce(): string {
    // `btoa` rather than `Buffer`, so this does not depend on a Node built-in. The UUID is ASCII,
    // so the emitted value matches the product application's byte for byte.
    return btoa(crypto.randomUUID());
}

function browserFacingRequestOrigin(request: NextRequest): string {
    const forwardedProtocol = request.headers.get('x-forwarded-proto');
    const protocol = forwardedProtocol === 'http' || forwardedProtocol === 'https'
        ? `${forwardedProtocol}:`
        : request.nextUrl.protocol;
    const host = request.headers.get('host');

    if (host) {
        try {
            const url = new URL(`${protocol}//${host}`);
            if (!url.username && !url.password && url.pathname === '/' && !url.search && !url.hash) {
                return url.origin;
            }
        } catch {}
    }

    return new URL(request.url).origin;
}

/**
 * Applies the same nonce-bearing CSP the product site serves.
 *
 * The prelaunch deployment has no sessions and no protected routes, so this carries the header work
 * only; route protection stays in the product application.
 */
export function proxy(request: NextRequest) {
    const { pathname, search } = request.nextUrl;
    const nonce = createNonce();
    const requestOrigin = browserFacingRequestOrigin(request);
    const reportingEndpointUrl = contentSecurityPolicyReportingEndpoint(requestOrigin);
    const reportingEndpointsHeader = createReportingEndpointsHeader(reportingEndpointUrl);
    const policy = createFrontendContentSecurityPolicy({
        nonce,
        requestUrl: requestOrigin,
        isDevelopment: process.env.NODE_ENV === 'development',
        configuredImageOrigins: process.env.CONNEX_CSP_IMAGE_ORIGINS,
        reportingEndpointUrl,
    });

    const requestHeaders = new Headers(request.headers);
    requestHeaders.set('x-pathname', pathname + search);
    requestHeaders.set('x-nonce', nonce);
    requestHeaders.set('Content-Security-Policy', policy);

    // The standard security headers come from `next.config.ts`, which also covers static assets.
    // Setting them here as well appends a second copy of each, and a repeated `X-Frame-Options` is
    // not a value browsers are obliged to honour.
    const response = NextResponse.next({ request: { headers: requestHeaders } });
    applyFrontendContentSecurityPolicy(
        response.headers,
        policy,
        resolveContentSecurityPolicyMode(process.env.CONNEX_CSP_MODE),
    );
    applyFrontendReportingEndpoints(response.headers, reportingEndpointsHeader);
    return response;
}

export const config = {
    matcher: [
        '/((?!api(?:/|$)|_next/static(?:/|$)|_next/image(?:/|$)|favicon\\.ico$|robots\\.txt$).*)',
    ],
};
