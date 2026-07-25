'use client';

import ErrorState, { type SegmentErrorProps } from '@/app/components/ErrorState';

/**
 * Root-level error boundary rendered inside the root layout's providers. It
 * catches failures thrown by segment layouts (including the app shell layout)
 * and public pages, keeping the localized recovery state ahead of the bare
 * global-error fallback.
 */
export default function RootError({ error, reset, unstable_retry }: SegmentErrorProps) {
    return <ErrorState error={error} retry={unstable_retry ?? reset} />;
}
