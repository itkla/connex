'use client';

import ErrorState, { type SegmentErrorProps } from '@/app/components/ErrorState';

/**
 * Error boundary for the records segment; renders the shared recovery state.
 */
export default function RecordsError({ error, retry }: SegmentErrorProps) {
    return <ErrorState error={error} retry={retry} />;
}
