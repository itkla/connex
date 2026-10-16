'use client';

import { useEffect, useState } from 'react';

import { useLiveNow } from '@/app/hooks/useNow';

/** Refreshes review controls at their next deadline and when a suspended view resumes. */
export function useProposalReviewNow(deadlines: readonly (string | null)[]): number {
    const liveNow = useLiveNow();
    const [observedNow, setObservedNow] = useState(0);
    const now = Math.max(liveNow, observedNow);
    const nextDeadline = deadlines.reduce((next, deadline) => {
        const parsed = typeof deadline === 'string' ? Date.parse(deadline) : NaN;
        return Number.isFinite(parsed) && parsed > observedNow ? Math.min(next, parsed) : next;
    }, Number.POSITIVE_INFINITY);

    useEffect(() => {
        if (!Number.isFinite(nextDeadline)) return;
        const refresh = () => setObservedNow((previous) => Math.max(previous, Date.now()));
        const timer = window.setTimeout(refresh, Math.min(
            Math.max(0, nextDeadline - Date.now()), 2_147_483_647,
        ));
        window.addEventListener('focus', refresh);
        document.addEventListener('visibilitychange', refresh);
        return () => {
            window.clearTimeout(timer);
            window.removeEventListener('focus', refresh);
            document.removeEventListener('visibilitychange', refresh);
        };
    }, [nextDeadline, observedNow]);

    return now;
}
