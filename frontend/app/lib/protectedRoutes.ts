const PROTECTED_PREFIXES = [
    '/account',
    '/activity',
    '/admin',
    '/dashboard',
    '/library',
    '/marketing',
    '/me',
    '/notifications',
    '/organization',
    '/overview',
    '/records',
    '/search',
    '/settings',
    '/users',
    '/workflows',
] as const;

/** Returns whether a pathname belongs to the authenticated, workspace-scoped application. */
export function isProtectedPath(pathname: string): boolean {
    return PROTECTED_PREFIXES.some(
        (prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`),
    );
}
