'use client';

import type { ReactNode } from 'react';
import { useTranslations } from 'next-intl';
import { ArrowPathIcon, ExclamationTriangleIcon } from '@heroicons/react/24/outline';

import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { ApiError } from '@/app/lib/api';

/** Keeps a failed collection distinct from an empty one while preserving previously loaded rows. */
export function RecordListState({
    error,
    loading,
    hasRecords,
    onRetry,
    children,
}: {
    error: Error | null;
    loading: boolean;
    hasRecords: boolean;
    onRetry: () => void;
    children: ReactNode;
}) {
    const t = useTranslations('RecordListState');
    const denied = error instanceof ApiError && error.status === 403;
    const reference = error instanceof ApiError ? error.correlationId : undefined;

    return (
        <>
            {error ? (
                <Alert className={hasRecords ? 'mb-4' : undefined}>
                    <ExclamationTriangleIcon aria-hidden />
                    <AlertTitle className="line-clamp-none">{t(denied ? 'deniedTitle' : 'title')}</AlertTitle>
                    <AlertDescription>
                        <p>{t(denied ? 'deniedBody' : hasRecords ? 'refreshBody' : 'body')}</p>
                        {reference ? <p className="break-all font-mono text-xs select-all">{t('reference', { id: reference })}</p> : null}
                        <Button variant="outline" size="toolbar" onClick={onRetry} disabled={loading} className="mt-2">
                            <ArrowPathIcon
                                data-icon="inline-start"
                                className={loading ? 'animate-spin motion-reduce:animate-none' : undefined}
                            />
                            {t(loading ? 'retrying' : 'retry')}
                        </Button>
                    </AlertDescription>
                </Alert>
            ) : null}
            {!error || hasRecords ? children : null}
        </>
    );
}
