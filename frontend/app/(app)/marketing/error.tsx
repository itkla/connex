'use client';

import ErrorState, { type SegmentErrorProps } from '@/app/components/ErrorState';

/**
 * Error boundary for the marketing segment; renders the shared recovery state.
 */
export default function MarketingError({ error, retry }: SegmentErrorProps) {
    return <ErrorState error={error} retry={retry} />;
}
