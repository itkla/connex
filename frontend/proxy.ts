import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

import { getCurrentUserFromCookie } from './app/lib/api';

export async function proxy(request: NextRequest) {
    const { pathname } = request.nextUrl;
    const isDashboardRoute = pathname.startsWith('/dashboard');

    if (pathname === '/auth/logout') {
        return NextResponse.next();
    }

    const cookie = request.headers.get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        return isDashboardRoute
            ? NextResponse.redirect(new URL('/auth/login', request.url))
            : NextResponse.next();
    }

    return isDashboardRoute
        ? NextResponse.next()
        : NextResponse.redirect(new URL('/dashboard', request.url));
}

export const config = {
    matcher: ['/auth/:path*', '/dashboard/:path*'],
}