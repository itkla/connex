'use client';

import {
    CheckCircleIcon,
    ClockIcon,
    ExclamationTriangleIcon,
} from '@heroicons/react/24/outline';
import { useLocale, useTranslations } from 'next-intl';

import {
    captureProgress,
    isCaptureOperationActive,
} from '@/app/lib/connectedCapture';
import type {
    CaptureHealthStatus,
    CaptureStream,
    CaptureStreamState,
} from '@/app/lib/types';
import { formatRelativeTime } from '@/app/lib/utils';
import { cn } from '@/lib/utils';

const STATUS_CLASS: Record<CaptureHealthStatus, string> = {
    idle: 'text-muted-foreground',
    queued: 'text-risk-medium',
    backfilling: 'text-risk-medium',
    syncing: 'text-risk-medium',
    retrying: 'text-risk-medium',
    intervention_required: 'text-destructive',
    paused: 'text-muted-foreground',
    purging: 'text-risk-medium',
};

const STREAM_KEYS: Record<CaptureStream, 'stream.calendar' | 'stream.mailInbox' | 'stream.mailSent'> = {
    calendar: 'stream.calendar',
    mail_inbox: 'stream.mailInbox',
    mail_sent: 'stream.mailSent',
};

function errorKey(code?: string | null) {
    switch (code) {
        case 'credential_missing':
        case 'refresh_token_missing':
        case 'reconnect_required':
        case 'token_expired': return 'error.tokenExpired';
        case 'connection_unavailable':
        case 'revoked': return 'error.revoked';
        case 'provider_rate_limited':
        case 'rate_limited': return 'error.rateLimited';
        case 'cursor_invalid': return 'error.cursorInvalid';
        case 'capture_failed':
        case 'provider_interrupted':
        case 'provider_retryable':
        case 'provider_unreachable':
        case 'provider_unavailable': return 'error.providerUnavailable';
        case 'intervention_required': return 'error.interventionRequired';
        case 'purge_failed': return 'error.purgeFailed';
        default: return 'error.generic';
    }
}

function StatusIcon({ status }: { status: CaptureHealthStatus }) {
    if (status === 'idle') {
        return <CheckCircleIcon className="size-4" aria-hidden />;
    }
    if (status !== 'intervention_required') {
        return <ClockIcon className="size-4" aria-hidden />;
    }
    return <ExclamationTriangleIcon className="size-4" aria-hidden />;
}

/**
 * Displays provider stream health with semantic progress and actionable error context.
 */
export default function CaptureHealth({ streams }: { streams: CaptureStreamState[] }) {
    const t = useTranslations('AccountCaptureHealth');
    const locale = useLocale();

    if (streams.length === 0) {
        return <p className="text-xs text-muted-foreground">{t('empty')}</p>;
    }

    return (
        <ul className="grid gap-2" aria-label={t('title')} aria-live="polite">
            {streams.map((stream) => {
                const progress = captureProgress(stream.processedItems, stream.estimatedItems);
                const active = isCaptureOperationActive(stream.status);
                return (
                    <li key={stream.stream} className="rounded-lg bg-muted/40 px-3 py-2.5">
                        <div className="flex items-start justify-between gap-3">
                            <div className="min-w-0">
                                <p className="text-xs font-medium text-foreground">
                                    {t(STREAM_KEYS[stream.stream])}
                                </p>
                                <p className={cn(
                                    'mt-0.5 flex items-center gap-1.5 text-xs',
                                    STATUS_CLASS[stream.status],
                                )}>
                                    <StatusIcon status={stream.status} />
                                    {t(`status.${stream.status}`)}
                                </p>
                            </div>
                            {stream.lastSuccessAt ? (
                                <span className="shrink-0 text-xs text-muted-foreground">
                                    {t('lastSuccess', {
                                        time: formatRelativeTime(stream.lastSuccessAt, locale),
                                    })}
                                </span>
                            ) : null}
                        </div>

                        {active ? (
                            <div className="mt-2">
                                {progress ? (
                                    <>
                                        <progress
                                            className="h-1.5 w-full accent-brand"
                                            value={progress.value}
                                            max={progress.max}
                                            aria-label={t('progressKnown', {
                                                processed: progress.value,
                                                total: progress.max,
                                            })}
                                        />
                                        <p className="mt-1 text-xs tabular-nums text-muted-foreground">
                                            {t('progressKnown', {
                                                processed: progress.value,
                                                total: progress.max,
                                            })}
                                        </p>
                                    </>
                                ) : (
                                    <p className="text-xs text-muted-foreground" role="status">
                                        {t('progressUnknown', { processed: stream.processedItems })}
                                    </p>
                                )}
                            </div>
                        ) : null}

                        {stream.errorCode ? (
                            <p className="mt-1.5 text-xs text-destructive" role="alert">
                                {t(errorKey(stream.errorCode))}
                            </p>
                        ) : null}

                        {stream.nextAttemptAt ? (
                            <p className="mt-1 text-xs text-muted-foreground">
                                {t('nextRetry', {
                                    time: formatRelativeTime(stream.nextAttemptAt, locale),
                                })}
                            </p>
                        ) : null}
                    </li>
                );
            })}
        </ul>
    );
}
