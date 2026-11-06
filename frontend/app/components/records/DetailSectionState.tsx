'use client';

import { useTransition, type ReactNode } from 'react';
import { useRouter } from 'next/navigation';

import { RecordListState } from '@/app/components/records/RecordListState';
import { ApiError } from '@/app/lib/api';
import type { DetailSectionFailure } from '@/app/lib/detailSectionResult';

/** Reuses collection recovery for server detail sections and re-fetches their data on Retry. */
export default function DetailSectionState({ failure, title, children }: {
    failure: DetailSectionFailure | null;
    title: string;
    children: ReactNode;
}) {
    const router = useRouter();
    const [retrying, startTransition] = useTransition();
    const error = failure
        ? new ApiError('', failure.status ?? 500, undefined, undefined, failure.reference)
        : null;

    return (
        <RecordListState
            error={error}
            loading={retrying}
            hasRecords={false}
            sectionTitle={title}
            onRetry={() => startTransition(() => router.refresh())}
        >
            {children}
        </RecordListState>
    );
}
