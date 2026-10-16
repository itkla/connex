'use client';

import ErrorState, { type SegmentErrorProps } from '@/app/components/ErrorState';

/**
 * Error boundary for the admin segment; renders the shared recovery state.
 */
export default function AdminError({ error, retry }: SegmentErrorProps) {
    return <ErrorState error={error} retry={retry} />;
}
