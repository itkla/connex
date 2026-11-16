'use client';

import ErrorState, { type SegmentErrorProps } from '@/app/components/ErrorState';

/**
 * Error boundary for the users segment; renders the shared recovery state.
 */
export default function UsersError({ error, retry }: SegmentErrorProps) {
    return <ErrorState error={error} retry={retry} />;
}
