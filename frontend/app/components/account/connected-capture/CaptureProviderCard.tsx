'use client';

import type { ReactNode } from 'react';
import { ArrowPathIcon, LinkIcon, TrashIcon } from '@heroicons/react/24/outline';
import { useLocale, useTranslations } from 'next-intl';

import { useLiveNow } from '@/app/hooks/useNow';
import {
    lastCaptureSuccessAt,
    providerCardAction,
    providerGlanceState,
    type ProviderGlanceSource,
    type ProviderJourneyState,
} from '@/app/lib/connectedCapture';
import { MANAGED_OAUTH_DOC_URL } from '@/app/lib/managedConnect';
import type {
    ConnectedAccountProvider,
    ProviderCaptureOverview,
    ProviderConnection,
    ProviderConnectionStatus,
} from '@/app/lib/types';
import { formatRelativeTime } from '@/app/lib/utils';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';

const STATUS_CLASS: Record<ProviderConnectionStatus, string> = {
    connected: 'bg-brand text-brand-foreground ring-brand',
    paused: 'bg-risk-medium/15 text-risk-medium ring-risk-medium/30',
    error: 'bg-destructive/15 text-destructive ring-destructive/30',
    revoked: 'bg-destructive/15 text-destructive ring-destructive/30',
    disconnecting: 'bg-risk-medium/15 text-risk-medium ring-risk-medium/30',
    purge_failed: 'bg-destructive/15 text-destructive ring-destructive/30',
};

const GLANCE_SOURCES: readonly ProviderGlanceSource[] = ['mail', 'calendar'];

const GLANCE_LABEL_KEYS: Record<ProviderGlanceSource, string> = {
    mail: 'stream.mail',
    calendar: 'stream.calendar',
};

/**
 * One row of the card's at-a-glance strip: a muted label above the value it names.
 *
 * Deliberately not a box. The strip sits inside the provider card, and a bordered tile inside a
 * bordered card inside a bordered panel is the nesting this card was rebuilt to remove.
 */
function GlanceItem({
    label,
    tone = 'normal',
    children,
}: {
    label: string;
    tone?: 'normal' | 'attention';
    children: ReactNode;
}) {
    return (
        <div className="min-w-0">
            <dt className="text-xs text-muted-foreground">{label}</dt>
            <dd className={cn(
                'mt-0.5 truncate text-sm font-medium',
                tone === 'attention' ? 'text-destructive' : 'text-foreground',
            )}>
                {children}
            </dd>
        </div>
    );
}

/**
 * One provider's place in the connected-accounts journey.
 *
 * The card carries only what the reader must decide from: disconnected, it states the concrete
 * value and offers the single action that starts authorization; connected, it states who is
 * connected, whether mail and calendar are running, and when capture last succeeded, then hands
 * everything else to the manage drawer. Sync lifecycle, capture policy, workspace defaults, the
 * review queue, disconnect, and erasure all live behind `Manage`, so the card never becomes a
 * control plane again.
 *
 * The credential mode is not named here. Which OAuth application an instance uses is an operator
 * concern, and the card says what the reader must do rather than how authorization is arranged.
 * The one exception is a managed application that cannot be used in this build: that blocks the
 * reader's own action, so it is stated in place instead of offering a connect that cannot succeed.
 *
 * @param state the journey state this card renders, derived once by the panel
 * @param capture the provider's capture overview, or null when capture is off or still loading
 * @param pendingReviews items waiting on this reader, surfaced only when there are any
 */
export default function CaptureProviderCard({
    provider,
    providerIcon,
    state,
    managedUnavailable,
    connection,
    connectionEnabled,
    captureEnabled,
    capture,
    captureLoading,
    captureLoadError,
    pendingReviews,
    authorizationErrorCode,
    busy,
    onConnect,
    onPurge,
    onReset,
    onManage,
    onReviews,
    onSync,
    onRetryCapture,
}: {
    provider: ConnectedAccountProvider;
    providerIcon: ReactNode;
    state: ProviderJourneyState;
    managedUnavailable: boolean;
    connection: ProviderConnection | null;
    connectionEnabled: boolean;
    captureEnabled: boolean;
    capture: ProviderCaptureOverview | null;
    captureLoading: boolean;
    captureLoadError: boolean;
    pendingReviews: number;
    authorizationErrorCode: string | null;
    busy: boolean;
    onConnect: () => void;
    onPurge: () => void;
    onReset: () => void;
    onManage: () => void;
    onReviews: () => void;
    onSync: () => void;
    onRetryCapture: () => void;
}) {
    const t = useTranslations('AccountConnections');
    const tCapture = useTranslations('AccountCaptureProvider');
    const locale = useLocale();
    const now = useLiveNow();
    const providerName = t(`provider_${provider}`);
    const lastSuccess = lastCaptureSuccessAt(capture);
    const action = providerCardAction(state, connection, captureEnabled, capture);
    const showSecondaryAction = action === 'reconnect' || action === 'sync';
    const stalledWithoutRepair = state === 'attention' && action !== 'reconnect';
    const resetFailed = connection?.status === 'purge_failed';

    return (
        <article className="rounded-2xl border border-border bg-card px-4 py-4 sm:px-5">
            <div className="flex flex-wrap items-start gap-3">
                <div className="grid size-10 shrink-0 place-items-center rounded-lg bg-muted ring-1 ring-border">
                    {providerIcon}
                </div>
                <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                        <h2 className="text-sm font-semibold text-foreground">{providerName}</h2>
                        {connection ? (
                            <span className={cn(
                                'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset',
                                STATUS_CLASS[connection.status],
                            )}>
                                {t(`status_${connection.status}`)}
                            </span>
                        ) : null}
                        {pendingReviews > 0 ? (
                            <Button
                                type="button"
                                variant="outline"
                                size="inline"
                                disabled={busy}
                                onClick={onReviews}
                            >
                                {tCapture('reviews', { count: pendingReviews })}
                            </Button>
                        ) : null}
                    </div>
                    <p className="mt-1 max-w-prose text-sm text-muted-foreground">
                        {connection
                            ? connection.providerAccountEmail ?? t('connectedNoEmail')
                            : t(`value_${provider}`)}
                    </p>
                </div>

                <div className="flex shrink-0 flex-wrap gap-2">
                    {connection ? (
                        <>
                            <Button
                                variant={showSecondaryAction ? 'outline' : 'default'}
                                size="toolbar"
                                onClick={onManage}
                                disabled={busy}
                            >
                                {t('manage')}
                            </Button>
                            {showSecondaryAction ? (
                                <Button
                                    size="toolbar"
                                    disabled={busy || state === 'syncing'}
                                    onClick={action === 'reconnect' ? onConnect : onSync}
                                >
                                    <ArrowPathIcon
                                        data-icon="inline-start"
                                        className={state === 'syncing'
                                            ? 'animate-spin motion-reduce:animate-none'
                                            : undefined}
                                    />
                                    {action === 'reconnect' ? t('reconnect') : tCapture('syncNow')}
                                </Button>
                            ) : null}
                            {resetFailed ? (
                                <Button
                                    type="button"
                                    variant="destructive"
                                    size="toolbar"
                                    disabled={busy}
                                    onClick={onReset}
                                >
                                    <ArrowPathIcon data-icon="inline-start" />
                                    {t('retryReset')}
                                </Button>
                            ) : null}
                        </>
                    ) : (
                        <>
                            <Button
                                size="toolbar"
                                onClick={onConnect}
                                disabled={!connectionEnabled || busy || managedUnavailable}
                            >
                                <LinkIcon data-icon="inline-start" />
                                {t('connectProvider', { provider: providerName })}
                            </Button>
                            {capture?.retainedData ? (
                                <Button
                                    type="button"
                                    variant="destructive"
                                    size="toolbar"
                                    disabled={busy}
                                    onClick={onPurge}
                                >
                                    <TrashIcon data-icon="inline-start" />
                                    {tCapture('purge')}
                                </Button>
                            ) : null}
                            {capture?.accountResetAvailable ? (
                                <Button
                                    type="button"
                                    variant="outline"
                                    size="toolbar"
                                    disabled={busy}
                                    onClick={onReset}
                                >
                                    <TrashIcon data-icon="inline-start" />
                                    {t('resetProviderAccount')}
                                </Button>
                            ) : null}
                        </>
                    )}
                </div>
            </div>

            {!connection && (capture?.retainedData || capture?.accountResetAvailable) ? (
                <div className="mt-4 space-y-2 border-t border-border pt-4 text-sm text-muted-foreground">
                    {capture.retainedData ? <p>{t('retainedDataNote')}</p> : null}
                    {capture.accountResetAvailable ? <p>{t('accountResetNote')}</p> : null}
                </div>
            ) : null}

            {authorizationErrorCode ? (
                <div className="mt-4 border-t border-border pt-4">
                    <p className="max-w-prose text-sm text-destructive" role="alert">
                        {t(`error_${authorizationErrorCode}`)}
                    </p>
                    {authorizationErrorCode === 'retained_data_reset_required'
                            && !capture?.accountResetAvailable ? (
                        <Button
                            type="button"
                            variant="destructive"
                            size="toolbar"
                            className="mt-3"
                            disabled={busy}
                            onClick={onReset}
                        >
                            <TrashIcon data-icon="inline-start" />
                            {t('eraseRetainedData')}
                        </Button>
                    ) : (
                        <Button
                            type="button"
                            variant="outline"
                            size="toolbar"
                            className="mt-3"
                            disabled={!connectionEnabled || busy || managedUnavailable}
                            onClick={onConnect}
                        >
                            <ArrowPathIcon data-icon="inline-start" />
                            {t('tryAgain')}
                        </Button>
                    )}
                </div>
            ) : null}

            {managedUnavailable ? (
                <div className="mt-4 border-t border-border pt-4">
                    <h3 className="text-sm font-medium text-foreground">
                        {t('managedUnavailableTitle')}
                    </h3>
                    <p className="mt-1 max-w-prose text-sm text-muted-foreground">
                        {t('managedUnavailableBody', { provider: providerName })}
                    </p>
                    <a
                        className="mt-2 inline-block text-sm text-primary underline underline-offset-4"
                        href={MANAGED_OAUTH_DOC_URL}
                        target="_blank"
                        rel="noreferrer"
                    >
                        {t('managedUnavailableLink')}
                    </a>
                </div>
            ) : null}

            {connection && captureEnabled ? (
                <div className="mt-4 border-t border-border pt-4">
                    {captureLoading ? (
                        <div className="grid gap-2" role="status" aria-label={tCapture('loading')}>
                            <Skeleton className="h-4 w-40" />
                            <Skeleton className="h-4 w-56" />
                        </div>
                    ) : captureLoadError || !capture ? (
                        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                            <p className="text-sm text-destructive" role="alert">
                                {tCapture('loadFailed')}
                            </p>
                            <Button type="button" variant="outline" size="toolbar" onClick={onRetryCapture}>
                                {tCapture('retry')}
                            </Button>
                        </div>
                    ) : (
                        <>
                            <dl className="grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-3">
                                {GLANCE_SOURCES.map((source) => {
                                    const glance = providerGlanceState(capture, source);
                                    return (
                                        <GlanceItem
                                            key={source}
                                            label={t(GLANCE_LABEL_KEYS[source])}
                                            tone={glance === 'attention' ? 'attention' : 'normal'}
                                        >
                                            {t(`streamState_${glance}`)}
                                        </GlanceItem>
                                    );
                                })}
                                <GlanceItem label={t('lastSyncLabel')}>
                                    {lastSuccess
                                        ? formatRelativeTime(lastSuccess, locale, now)
                                        : t('lastSyncNever')}
                                </GlanceItem>
                            </dl>
                            {stalledWithoutRepair ? (
                                <p className="mt-3 text-sm text-destructive" role="alert">
                                    {tCapture('stalledSource')}
                                </p>
                            ) : null}
                        </>
                    )}
                </div>
            ) : null}

            {connection && !captureEnabled ? (
                <p className="mt-4 border-t border-border pt-4 text-sm text-muted-foreground">
                    {tCapture('captureOff')}
                </p>
            ) : null}
        </article>
    );
}
