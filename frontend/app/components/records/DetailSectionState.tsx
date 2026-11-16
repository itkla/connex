'use client';

import { useRef, useState, type ReactNode } from 'react';
import { useTranslations } from 'next-intl';

import { RecordListState } from '@/app/components/records/RecordListState';
import { ApiError } from '@/app/lib/api';
import { detailSectionFailure, type DetailSectionFailure } from '@/app/lib/detailSectionResult';
import { useDetailSections, type DetailSectionKey } from '@/app/hooks/useDetailSections';

/** Recovers only failed dependencies and announces success without moving focus away from other work. */
export default function DetailSectionState({ failure = null, sectionKeys = [], title, children }: {
    failure?: DetailSectionFailure | null;
    sectionKeys?: readonly DetailSectionKey[];
    title: string;
    children: ReactNode;
}) {
    const sections = useDetailSections();
    const t = useTranslations('RecordListState');
    const region = useRef<HTMLDivElement>(null);
    const [recovered, setRecovered] = useState(false);
    const current = sections && sectionKeys.length > 0
        ? detailSectionFailure(...sectionKeys.flatMap((key) => sections.results[key] ? [sections.results[key]] : []))
        : failure;
    const error = current ? new ApiError('', current.status ?? 500, undefined, undefined, current.reference) : null;
    const loading = sectionKeys.some((key) => sections?.pending.has(key));
    const retry = async () => {
        const focused = document.activeElement;
        const ownsFocus = focused instanceof HTMLElement && region.current?.contains(focused);
        const success = await sections?.retry(sectionKeys);
        if (!success) return;
        setRecovered(true);
        if (ownsFocus && (document.activeElement === focused || document.activeElement === document.body)) {
            region.current?.focus({ preventScroll: true });
        }
    };

    return (
        <div ref={region} tabIndex={-1} role="region" aria-label={title} className="min-w-0 rounded-md focus-visible:outline-2 focus-visible:outline-ring">
            <p role="status" aria-live="polite" aria-atomic="true" className="sr-only">
                {recovered && !current ? t('sectionRecovered', { section: title }) : ''}
            </p>
            <RecordListState error={error} loading={loading} hasRecords={false} sectionTitle={title} onRetry={() => { void retry(); }}>
                {children}
            </RecordListState>
        </div>
    );
}
