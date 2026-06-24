import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

const PROTECTED_PREFIXES = [
    '/dashboard',
    '/me',
    '/records',
    '/library',
    '/activity',
    '/notifications',
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
    if (pathname.startsWith('/invite/')) {
        if (!hasSession) {
            const loginUrl = new URL('/auth/login', request.url);
            loginUrl.searchParams.set('redirect', pathname + search);
            return NextResponse.redirect(loginUrl);
        }
        return NextResponse.next();
    }

    if (hasSession && pathname.startsWith('/auth/') && !searchParams.has('redirect')) {
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
        '/dashboard/:path*',
        '/me/:path*',
        '/records/:path*',
        '/library/:path*',
        '/activity/:path*',
        '/notifications/:path*',
        '/onboarding',
        '/invite/:path*',
    ],
};
