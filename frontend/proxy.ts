import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

import { isProtectedPath } from '@/app/lib/protectedRoutes';
import {
    applyFrontendContentSecurityPolicy,
    applyFrontendSecurityHeaders,
    createFrontendContentSecurityPolicy,
    resolveContentSecurityPolicyMode,
} from '@/security-headers';

const SESSION_COOKIE = 'JSESSIONID';

const ALWAYS_ACCESSIBLE_AUTH_PATHS = new Set([
    '/auth/register',
    '/auth/forgot-password',
    '/auth/reset-password',
    '/auth/confirm-email',
]);

function createNonce(): string {
    return Buffer.from(crypto.randomUUID()).toString('base64');
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
            if (
                !url.username
                && !url.password
                && url.pathname === '/'
                && !url.search
                && !url.hash
            ) {
                return url.origin;
            }
        } catch {}
    }

    return new URL(request.url).origin;
}

function protectedResponse(response: NextResponse, policy: string): NextResponse {
    applyFrontendSecurityHeaders(response.headers);
    applyFrontendContentSecurityPolicy(
        response.headers,
        policy,
        resolveContentSecurityPolicyMode(process.env.CONNEX_CSP_MODE),
    );
    return response;
}

function forwardedResponse(request: NextRequest, nonce: string, policy: string): NextResponse {
    const { pathname, search } = request.nextUrl;
    const requestHeaders = new Headers(request.headers);
    requestHeaders.set('x-pathname', pathname + search);
    requestHeaders.set('x-nonce', nonce);
    requestHeaders.set('Content-Security-Policy', policy);
    return protectedResponse(NextResponse.next({ request: { headers: requestHeaders } }), policy);
}

/** Applies route protection and a per-request nonce-bearing CSP to frontend HTML routes. */
export function proxy(request: NextRequest) {
    const { pathname, search, searchParams } = request.nextUrl;
    const hasSession = request.cookies.has(SESSION_COOKIE);
    const nonce = createNonce();
    const policy = createFrontendContentSecurityPolicy({
        nonce,
        requestUrl: browserFacingRequestOrigin(request),
        isDevelopment: process.env.NODE_ENV === 'development',
        configuredWebSocketUrl: process.env.NEXT_PUBLIC_WS_URL,
        configuredImageOrigins: process.env.CONNEX_CSP_IMAGE_ORIGINS,
    });
    const redirect = (url: URL) => protectedResponse(NextResponse.redirect(url), policy);
    const next = () => forwardedResponse(request, nonce, policy);

    // Onboarding needs a session but must stay reachable even with a leftover
    // connex_workspace cookie: after involuntary membership removal that cookie
    // can still name a workspace the caller no longer belongs to (#1108). Bouncing
    // to /dashboard here would loop with the app shell's empty-membership redirect.
    if (pathname === '/onboarding') {
        if (!hasSession) {
            const loginUrl = new URL('/auth/login', request.url);
            loginUrl.searchParams.set('redirect', pathname);
            return redirect(loginUrl);
        }
        return next();
    }

    if (pathname === '/auth/logout') {
        return next();
    }

    // Accepting an invite needs a session but no workspace (invite-only users have none yet).
    if (pathname.startsWith('/invite/') || pathname.startsWith('/invite-link/')) {
        if (!hasSession) {
            const loginUrl = new URL('/auth/login', request.url);
            loginUrl.searchParams.set('redirect', pathname + search);
            return redirect(loginUrl);
        }
        return next();
    }

    // The session cookie's presence doesn't prove the session is still valid — a stale JSESSIONID
    // can linger after it expires server-side. Registration and account-recovery pages are how a
    // user re-authenticates or starts fresh, so they stay reachable regardless of a lingering
    // cookie; bouncing them to the dashboard would trap anyone holding a dead session.
    if (
        hasSession &&
        pathname.startsWith('/auth/') &&
        !ALWAYS_ACCESSIBLE_AUTH_PATHS.has(pathname) &&
        !searchParams.has('redirect')
    ) {
        return redirect(new URL('/dashboard', request.url));
    }

    if (!hasSession && isProtectedPath(pathname) && pathname !== '/auth/register') {
        const loginUrl = new URL('/auth/login', request.url);
        loginUrl.searchParams.set('redirect', pathname + search);
        return redirect(loginUrl);
    }

    return next();
}

export const config = {
    matcher: [
        '/((?!api(?:/|$)|saml2(?:/|$)|_next/static(?:/|$)|_next/image(?:/|$)|favicon\\.ico$|sitemap\\.xml$|robots\\.txt$).*)',
    ],
};
