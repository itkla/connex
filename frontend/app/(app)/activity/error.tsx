'use client';

import ErrorState, { type SegmentErrorProps } from '@/app/components/ErrorState';

/**
 * Error boundary for the activity segment; renders the shared recovery state.
 */
export default function ActivityError({ error, retry }: SegmentErrorProps) {
    return <ErrorState error={error} retry={retry} />;
}
