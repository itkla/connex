'use client';

import ErrorState, { type SegmentErrorProps } from '@/app/components/ErrorState';

/**
 * Error boundary for the settings segment; renders the shared recovery state.
 */
export default function SettingsError({ error, retry }: SegmentErrorProps) {
    return <ErrorState error={error} retry={retry} />;
}
