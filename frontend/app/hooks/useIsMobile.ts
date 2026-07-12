'use client';

import { useSyncExternalStore } from 'react';

const MOBILE_QUERY = '(max-width: 767px)';

/**
 * Tracks whether the viewport is below the `md` breakpoint (768px) via `matchMedia`.
 * SSR-safe: the server snapshot resolves to desktop (`false`) so first paint matches the
 * server, then the real value is applied on hydration and kept live as the viewport changes.
 * Use it to switch a control between its desktop form (e.g. a centered dialog) and its mobile
 * form (e.g. a bottom drawer).
 */
export function useIsMobile(): boolean {
    return useSyncExternalStore(
        (onChange) => {
            const mql = window.matchMedia(MOBILE_QUERY);
            mql.addEventListener('change', onChange);
            return () => mql.removeEventListener('change', onChange);
        },
        () => window.matchMedia(MOBILE_QUERY).matches,
        () => false,
    );
}
