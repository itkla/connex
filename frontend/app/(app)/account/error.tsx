'use client';

import ErrorState, { type SegmentErrorProps } from '@/app/components/ErrorState';

/**
 * Error boundary for the account segment; renders the shared recovery state.
 */
export default function AccountError({ error, retry }: SegmentErrorProps) {
    return <ErrorState error={error} retry={retry} />;
}
