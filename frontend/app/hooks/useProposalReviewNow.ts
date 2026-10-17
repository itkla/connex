'use client';

import { useEffect, useState, useSyncExternalStore } from 'react';

import { useNow } from '@/app/hooks/useNow';
import {
    proposalReviewClockSnapshot,
    proposalReviewNow,
    subscribeProposalReviewClock,
} from '@/app/lib/proposalReviewClock';

const serverSnapshot = () => null;

/** Refreshes review controls at their next deadline and when a suspended view resumes. */
export function useProposalReviewNow(deadlines: readonly (string | null)[]): number {
    const renderNow = useNow();
    const clock = useSyncExternalStore(
        subscribeProposalReviewClock, proposalReviewClockSnapshot, serverSnapshot,
    );
    const [observed, setObserved] = useState({ clock, now: clock?.timestamp ?? renderNow });
    const now = observed.clock === clock ? observed.now : clock?.timestamp ?? renderNow;
    const nextDeadline = deadlines.reduce((next, deadline) => {
        const parsed = typeof deadline === 'string' ? Date.parse(deadline) : NaN;
        return Number.isFinite(parsed) && parsed > now ? Math.min(next, parsed) : next;
    }, Number.POSITIVE_INFINITY);

    useEffect(() => {
        if (clock === null) return;
        let timer: number | undefined;
        const refresh = () => {
            const current = proposalReviewNow();
            setObserved({ clock, now: current });
            window.clearTimeout(timer);
            if (Number.isFinite(nextDeadline) && current < nextDeadline) {
                timer = window.setTimeout(refresh, Math.min(
                    Math.ceil(nextDeadline - current), 2_147_483_647,
                ));
            }
        };
        refresh();
        if (!Number.isFinite(nextDeadline)) return;
        window.addEventListener('focus', refresh);
        document.addEventListener('visibilitychange', refresh);
        return () => {
            window.clearTimeout(timer);
            window.removeEventListener('focus', refresh);
            document.removeEventListener('visibilitychange', refresh);
        };
    }, [clock, nextDeadline]);

    return now;
}
