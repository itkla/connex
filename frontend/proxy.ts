import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

const PROTECTED_PREFIXES = [
    '/dashboard',
    '/me',
    '/records',
    '/library',
    '/activity',
];

const SESSION_COOKIE = 'JSESSIONID';

function isProtectedPath(pathname: string) {
    return PROTECTED_PREFIXES.some(
        (prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`),
    );
}

export function proxy(request: NextRequest) {
    const { pathname, search } = request.nextUrl;
    const hasSession = request.cookies.has(SESSION_COOKIE);

    if (pathname === '/auth/logout') {
        return NextResponse.next();
    }

    if (hasSession && pathname.startsWith('/auth/')) {
        return NextResponse.redirect(new URL('/dashboard', request.url));
    }

    if (!hasSession && isProtectedPath(pathname)) {
        const loginUrl = new URL('/auth/login', request.url);
        loginUrl.searchParams.set('redirect', pathname + search);
        return NextResponse.redirect(loginUrl);
    }

    return NextResponse.next();
}

export const config = {
    matcher: [
        '/auth/:path*',
        '/dashboard/:path*',
        '/me/:path*',
        '/records/:path*',
        '/library/:path*',
        '/activity/:path*',
    ],
};
