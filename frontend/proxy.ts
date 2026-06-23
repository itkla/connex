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

function isProtectedPath(pathname: string) {
    return PROTECTED_PREFIXES.some(
        (prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`),
    );
}

export function proxy(request: NextRequest) {
    const { pathname, search, searchParams } = request.nextUrl;
    const hasSession = request.cookies.has(SESSION_COOKIE);

    if (pathname === '/auth/logout') {
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
    ],
};
