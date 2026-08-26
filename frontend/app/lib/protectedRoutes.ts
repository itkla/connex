const PROTECTED_PREFIXES = [
    '/account',
    '/activity',
    '/admin',
    '/ask-connex',
    '/dashboard',
    '/insights',
    '/intelligence',
    '/library',
    '/marketing',
    '/me',
    '/notifications',
    '/organization',
    '/overview',
    '/radar',
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
