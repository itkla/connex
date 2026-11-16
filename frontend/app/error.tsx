'use client';

import ErrorState, { type SegmentErrorProps } from '@/app/components/ErrorState';

/**
 * Root-level error boundary rendered inside the root layout's providers. It
 * catches failures thrown by segment layouts (including the app shell layout)
 * and public pages, keeping the localized recovery state ahead of the bare
 * global-error fallback.
 *
 * It recovers with `reset` rather than Next's router-refreshing `retry`. The
 * one-time-link entry routes sit under this boundary, and a refresh re-publishes
 * whatever canonical URL the router holds, bearer included if the strip's sync
 * never reached it (#1781). The cost is that "Try again" here re-renders from
 * client state without re-fetching, so a failure thrown by a server layout
 * recovers only on a reload.
 */
export default function RootError({ error, reset }: SegmentErrorProps) {
    return <ErrorState error={error} retry={reset} />;
}
