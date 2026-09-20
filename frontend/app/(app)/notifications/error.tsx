'use client';

import ErrorState, { type SegmentErrorProps } from '@/app/components/ErrorState';

/**
 * Error boundary for the notifications segment; renders the shared recovery state.
 */
export default function NotificationsError({ error, reset, unstable_retry }: SegmentErrorProps) {
    return <ErrorState error={error} retry={unstable_retry ?? reset} />;
}
