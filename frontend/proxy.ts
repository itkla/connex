import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

import { isProtectedPath } from '@/app/lib/protectedRoutes';

const SESSION_COOKIE = 'JSESSIONID';
const WORKSPACE_COOKIE = 'connex_workspace';

const ALWAYS_ACCESSIBLE_AUTH_PATHS = new Set([
    '/auth/register',
    '/auth/forgot-password',
    '/auth/reset-password',
    '/auth/confirm-email',
]);

export function proxy(request: NextRequest) {
    const { pathname, search, searchParams } = request.nextUrl;
    const hasSession = request.cookies.has(SESSION_COOKIE);
    const hasWorkspace = request.cookies.has(WORKSPACE_COOKIE);

    // Onboarding (create a workspace): session required; skip it once a workspace exists.
    if (pathname === '/onboarding') {
        if (!hasSession) {
            const loginUrl = new URL('/auth/login', request.url);
            loginUrl.searchParams.set('redirect', pathname);
            return NextResponse.redirect(loginUrl);
        }
        if (hasWorkspace) {
            return NextResponse.redirect(new URL('/dashboard', request.url));
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
        '/records/:path*',
        '/search/:path*',
        '/settings/:path*',
        '/users/:path*',
        '/workflows/:path*',
        '/onboarding',
        '/invite/:path*',
        '/invite-link/:path*',
    ],
};
