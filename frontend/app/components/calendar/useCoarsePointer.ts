'use client';

import { useEffect, useState } from 'react';

/**
 * True on touch-first devices (coarse pointer). Defaults to false so SSR and the first
 * client render agree; the real value is applied after mount. Used to make swipe the
 * primary gesture on touch and drag-to-reschedule the primary gesture on pointer devices,
 * keeping the two gesture systems from competing for the same pointer.
 */
export function useCoarsePointer(): boolean {
    const [coarse, setCoarse] = useState(false);

    useEffect(() => {
        const mql = window.matchMedia('(pointer: coarse)');
        const apply = () => setCoarse(mql.matches);
        const raf = window.requestAnimationFrame(apply);
        mql.addEventListener('change', apply);
        return () => {
            window.cancelAnimationFrame(raf);
            mql.removeEventListener('change', apply);
        };
    }, []);

    return coarse;
}
