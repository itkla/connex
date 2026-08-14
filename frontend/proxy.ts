import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

import { isProtectedPath } from '@/app/lib/protectedRoutes';
import { applyFrontendSecurityHeaders } from '@/security-headers';

const SESSION_COOKIE = 'JSESSIONID';
const BACKEND_URL = process.env.BACKEND_URL ?? 'http://localhost:8080';

const ALWAYS_ACCESSIBLE_AUTH_PATHS = new Set([
    '/auth/register',
    '/auth/forgot-password',
    '/auth/reset-password',
    '/auth/confirm-email',
]);

export function proxy(request: NextRequest) {
    const { pathname, search, searchParams } = request.nextUrl;
    const hasSession = request.cookies.has(SESSION_COOKIE);

    if (pathname === '/api' || pathname.startsWith('/api/') || pathname === '/saml2' || pathname.startsWith('/saml2/')) {
        const backendUrl = new URL(pathname + search, BACKEND_URL);
        const response = NextResponse.rewrite(backendUrl);
        applyFrontendSecurityHeaders(response.headers);
        return response;
    }

    // Onboarding needs a session but must stay reachable even with a leftover
    // connex_workspace cookie: after involuntary membership removal that cookie
    // can still name a workspace the caller no longer belongs to (#1108). Bouncing
    // to /dashboard here would loop with the app shell's empty-membership redirect.
    if (pathname === '/onboarding') {
        if (!hasSession) {
            const loginUrl = new URL('/auth/login', request.url);
            loginUrl.searchParams.set('redirect', pathname);
            return NextResponse.redirect(loginUrl);
        }
        return NextResponse.next();
    }

    if (pathname === '/auth/logout') {
        return NextResponse.next();
    }

    // Accepting an invite needs a session but no workspace (invite-only users have none yet).
    if (pathname.startsWith('/invite/') || pathname.startsWith('/invite-link/')) {
        if (!hasSession) {
            const loginUrl = new URL('/auth/login', request.url);
            loginUrl.searchParams.set('redirect', pathname + search);
            return NextResponse.redirect(loginUrl);
        }
        return NextResponse.next();
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
        return NextResponse.redirect(new URL('/dashboard', request.url));
    }

    if (!hasSession && isProtectedPath(pathname) && pathname !== '/auth/register') {
        const loginUrl = new URL('/auth/login', request.url);
        loginUrl.searchParams.set('redirect', pathname + search);
        return NextResponse.redirect(loginUrl);
    }

    const requestHeaders = new Headers(request.headers);
    requestHeaders.set('x-pathname', pathname + search);
    return NextResponse.next({ request: { headers: requestHeaders } });
}

export const config = {
    matcher: [
        '/auth/:path*',
        '/account/:path*',
        '/activity/:path*',
        '/admin/:path*',
        '/dashboard/:path*',
        '/library/:path*',
        '/marketing/:path*',
        '/me/:path*',
        '/notifications/:path*',
        '/organization/:path*',
        '/overview/:path*',
        '/radar/:path*',
        '/records/:path*',
        '/search/:path*',
        '/settings/:path*',
        '/users/:path*',
        '/workflows/:path*',
        '/onboarding',
        '/invite/:path*',
        '/invite-link/:path*',
        '/api/:path*',
        '/saml2/:path*',
    ],
};
