import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

const PROTECTED_PREFIXES = [
    '/activity',
    '/admin',
    '/dashboard',
    '/library',
    '/me',
    '/notifications',
    '/overview',
    '/records',
    '/search',
    '/settings',
    '/users',
];

const SESSION_COOKIE = 'JSESSIONID';
const WORKSPACE_COOKIE = 'connex_workspace';

function isProtectedPath(pathname: string) {
    return PROTECTED_PREFIXES.some(
        (prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`),
    );
}

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

    // A logged-in user may legitimately follow a reset or email-verification link (e.g. right
    // after registering, or from another device), so let those through instead of bouncing them
    // to the dashboard.
    if (
        hasSession &&
        pathname.startsWith('/auth/') &&
        pathname !== '/auth/reset-password' &&
        pathname !== '/auth/confirm-email' &&
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
        '/activity/:path*',
        '/admin/:path*',
        '/dashboard/:path*',
        '/library/:path*',
        '/me/:path*',
        '/notifications/:path*',
        '/overview/:path*',
        '/records/:path*',
        '/search/:path*',
        '/settings/:path*',
        '/users/:path*',
        '/onboarding',
        '/invite/:path*',
        '/invite-link/:path*',
    ],
};
