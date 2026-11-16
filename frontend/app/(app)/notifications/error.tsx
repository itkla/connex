'use client';

import ErrorState, { type SegmentErrorProps } from '@/app/components/ErrorState';

/**
 * Error boundary for the notifications segment; renders the shared recovery state.
 */
export default function NotificationsError({ error, retry }: SegmentErrorProps) {
    return <ErrorState error={error} retry={retry} />;
}
