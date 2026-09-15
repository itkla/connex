import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

import {
    applyFrontendContentSecurityPolicy,
    applyFrontendReportingEndpoints,
    createFrontendContentSecurityPolicy,
    createReportingEndpointsHeader,
    resolveContentSecurityPolicyMode,
} from './security-headers';

/**
 * Creates a per-request CSP nonce. Uses `btoa` rather than `Buffer` so it does not depend on a Node
 * built-in; the UUID is ASCII, so the value matches the product application's byte for byte.
 * @returns a base64 nonce
 */
function createNonce(): string {
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
 *
 * Only the CSP and reporting headers are set here. The standard security headers come from
 * `next.config.ts` and `public/_headers`; setting them here too appends a second copy of each, and a
 * repeated `X-Frame-Options` is not a value browsers are obliged to honour.
 *
 * No CSP reporting endpoint is advertised: the product application's collector is a backend route,
 * and this deployment has no backend to receive the reports.
 *
 * `www.` hosts redirect permanently to the apex, so each page has exactly one canonical address and
 * the per-request `metadataBase`, sitemap, and Open Graph URLs never name the `www` duplicate.
 * @param request the incoming request
 * @returns a redirect to the apex, or the pass-through response carrying the policy
 */
export function proxy(request: NextRequest) {
    const { pathname, search } = request.nextUrl;
    const requestOrigin = browserFacingRequestOrigin(request);
    const origin = new URL(requestOrigin);
    if (origin.hostname.startsWith('www.')) {
        origin.hostname = origin.hostname.slice('www.'.length);
        return NextResponse.redirect(new URL(pathname + search, origin), 308);
    }

    const nonce = createNonce();
    const reportingEndpointUrl = null;
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
