'use client';

import { useEffect, useState } from 'react';

/**
 * True when the given media query matches. Defaults to false so SSR and the first client
 * render agree; the real value is applied after mount and kept live as the viewport changes.
 * Used to branch day-tap behavior by breakpoint — drill into the day view on narrow screens,
 * fill the side pane on wide ones.
 */
export function useMediaQuery(query: string): boolean {
    const [matches, setMatches] = useState(false);

    useEffect(() => {
        const mql = window.matchMedia(query);
        const apply = () => setMatches(mql.matches);
        const raf = window.requestAnimationFrame(apply);
        mql.addEventListener('change', apply);
        return () => {
            window.cancelAnimationFrame(raf);
            mql.removeEventListener('change', apply);
        };
    }, [query]);

    return matches;
}
