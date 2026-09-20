'use client';

import ErrorState, { type SegmentErrorProps } from '@/app/components/ErrorState';

/**
 * Error boundary for the search segment; renders the shared recovery state.
 */
export default function SearchError({ error, retry }: SegmentErrorProps) {
    return <ErrorState error={error} retry={retry} />;
}
