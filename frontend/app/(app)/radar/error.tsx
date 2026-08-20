'use client';

import ErrorState, { type SegmentErrorProps } from '@/app/components/ErrorState';
import { PageShell } from '@/app/components/PageShell';

/** Radar segment boundary with the shared retry and support-reference treatment. */
export default function RadarError({ error, reset, unstable_retry }: SegmentErrorProps) {
    return (
        <PageShell>
            <ErrorState error={error} retry={unstable_retry ?? reset} />
        </PageShell>
    );
}
