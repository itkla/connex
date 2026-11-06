'use client';

import { useEffect, type ReactNode } from 'react';
import { useTranslations } from 'next-intl';
import { ArrowPathIcon, ExclamationTriangleIcon } from '@heroicons/react/24/outline';

import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { ApiError } from '@/app/lib/api';
import { isSessionExpired, redirectToSignIn } from '@/app/lib/sessionExpiry';

/** Distinguishes retryable collection failures, empty results, access denials and expired sessions. */
export function RecordListState({
    error,
    loading,
    hasRecords,
    onRetry,
    sectionTitle,
    children,
}: {
    error: Error | null;
    loading: boolean;
    hasRecords: boolean;
    onRetry: () => void;
    sectionTitle?: string;
    children: ReactNode;
}) {
    const t = useTranslations('RecordListState');
    const sessionExpired = error !== null && isSessionExpired(error);
    const denied = error !== null && error instanceof ApiError && error.status === 403;
    const reference = error !== null && error instanceof ApiError ? error.correlationId : undefined;
    const showRecords = !error || (hasRecords && !denied);

    useEffect(() => {
        if (sessionExpired) redirectToSignIn();
    }, [sessionExpired]);

    if (sessionExpired) return null;

    return (
        <>
            {error ? (
                <Alert className={showRecords ? 'mb-4' : undefined}>
                    <ExclamationTriangleIcon aria-hidden />
                    <AlertTitle className="line-clamp-none">{sectionTitle
                        ? t(denied ? 'sectionDeniedTitle' : 'sectionTitle', { section: sectionTitle })
                        : t(denied ? 'deniedTitle' : 'title')}</AlertTitle>
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
            {showRecords ? children : null}
        </>
    );
}
