'use client';

import type { ReactNode } from 'react';
import {
    AdjustmentsHorizontalIcon,
    ArrowPathIcon,
    EllipsisHorizontalIcon,
    LinkIcon,
    PauseIcon,
    PlayIcon,
    ShieldCheckIcon,
    TrashIcon,
    UserGroupIcon,
} from '@heroicons/react/24/outline';
import { useTranslations } from 'next-intl';

import CaptureHealth from '@/app/components/account/connected-capture/CaptureHealth';
import type {
    ConnectedAccountProvider,
    ProviderCaptureOverview,
    ProviderConnection,
    ProviderConnectionStatus,
} from '@/app/lib/types';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';

const STATUS_CLASS: Record<ProviderConnectionStatus, string> = {
    connected: 'bg-brand text-brand-foreground ring-brand',
    paused: 'bg-risk-medium/15 text-risk-medium ring-risk-medium/30',
    error: 'bg-destructive/15 text-destructive ring-destructive/30',
    revoked: 'bg-muted text-muted-foreground ring-border',
    disconnecting: 'bg-risk-medium/15 text-risk-medium ring-risk-medium/30',
    purge_failed: 'bg-destructive/15 text-destructive ring-destructive/30',
};

function captureState(overview: ProviderCaptureOverview): 'ready' | 'configured' | 'notIngesting' | 'notConfigured' {
    if (!overview.userPolicy.enabled) return 'notConfigured';
    if (!overview.effectivePolicy.enabled) return 'notIngesting';
    return overview.activationReady ? 'ready' : 'configured';
}

/**
 * Shows OAuth custody and capture readiness as separate states for one provider.
 */
export default function CaptureProviderCard({
    provider,
    providerIcon,
    connection,
    connectionEnabled,
    captureEnabled,
    capture,
    captureLoading,
    captureLoadError,
    canManageWorkspacePolicy,
    busy,
    onConnect,
    onTogglePause,
    onConfigure,
    onWorkspacePolicy,
    onSync,
    onReviews,
    onPurge,
    onDisconnect,
    onRetryCapture,
}: {
    provider: ConnectedAccountProvider;
    providerIcon: ReactNode;
    connection: ProviderConnection | null;
    connectionEnabled: boolean;
    captureEnabled: boolean;
    capture: ProviderCaptureOverview | null;
    captureLoading: boolean;
    captureLoadError: boolean;
    canManageWorkspacePolicy: boolean;
    busy: boolean;
    onConnect: () => void;
    onTogglePause: () => void;
    onConfigure: () => void;
    onWorkspacePolicy: () => void;
    onSync: () => void;
    onReviews: () => void;
    onPurge: () => void;
    onDisconnect: () => void;
    onRetryCapture: () => void;
}) {
    const t = useTranslations('AccountConnections');
    const tCapture = useTranslations('AccountCaptureProvider');
    const needsReconnect = capture?.effectivePolicy.restrictionCodes.some(
        (code) => code === 'not_connected'
            || code === 'connection_error'
            || code === 'connection_revoked',
    ) ?? false;

    return (
        <article className="overflow-hidden rounded-2xl border border-border bg-card">
            <div className="flex items-start gap-3 px-4 py-4 sm:px-5">
                <div className="grid size-10 shrink-0 place-items-center rounded-lg bg-muted ring-1 ring-border">
                    {providerIcon}
                </div>
                <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                        <h2 className="text-sm font-semibold text-foreground">
                            {t(`provider_${provider}`)}
                        </h2>
                        {connection ? (
                            <span className={cn(
                                'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset',
                                STATUS_CLASS[connection.status],
                            )}>
                                {t(`status_${connection.status}`)}
                            </span>
                        ) : null}
                    </div>
                    <p className="mt-0.5 truncate text-xs text-muted-foreground">
                        {connection
                            ? connection.providerAccountEmail
                                ? t('connectedAs', { email: connection.providerAccountEmail })
                                : t('connectedNoEmail')
                            : t('notConnected')}
                    </p>
                </div>

                {connection ? (
                    <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                            <Button
                                variant="ghost"
                                size="icon-xs"
                                aria-label={t('actions')}
                                disabled={busy}
                            >
                                <EllipsisHorizontalIcon className="size-4" />
                            </Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end">
                            {(connection.status === 'connected' || connection.status === 'paused') ? (
                                <DropdownMenuItem onSelect={onTogglePause}>
                                    {connection.status === 'paused' ? (
                                        <><PlayIcon className="size-4" />{t('resume')}</>
                                    ) : (
                                        <><PauseIcon className="size-4" />{t('pause')}</>
                                    )}
                                </DropdownMenuItem>
                            ) : null}
                            {connectionEnabled ? (
                                <DropdownMenuItem onSelect={onConnect}>
                                    <ArrowPathIcon className="size-4" />
                                    {t('reconnect')}
                                </DropdownMenuItem>
                            ) : null}
                            {captureEnabled && capture && canManageWorkspacePolicy ? (
                                <DropdownMenuItem onSelect={onWorkspacePolicy}>
                                    <ShieldCheckIcon className="size-4" />
                                    {tCapture('workspacePolicy')}
                                </DropdownMenuItem>
                            ) : null}
                            {captureEnabled && capture ? (
                                <DropdownMenuItem onSelect={onPurge}>
                                    <TrashIcon className="size-4" />
                                    {tCapture('purge')}
                                </DropdownMenuItem>
                            ) : null}
                            <DropdownMenuSeparator />
                            <DropdownMenuItem variant="destructive" onSelect={onDisconnect}>
                                <TrashIcon className="size-4" />
                                {t('disconnect')}
                            </DropdownMenuItem>
                        </DropdownMenuContent>
                    </DropdownMenu>
                ) : (
                    <Button
                        variant="outline"
                        size="sm"
                        onClick={onConnect}
                        disabled={!connectionEnabled || busy}
                    >
                        <LinkIcon className="size-4" />
                        {t('connect')}
                    </Button>
                )}
            </div>

            {captureEnabled ? (
                <div className="border-t border-border bg-muted/20 px-4 py-4 sm:px-5">
                    {!connection ? (
                        <div>
                            <h3 className="text-sm font-medium text-foreground">{tCapture('title')}</h3>
                            <p className="mt-1 text-xs text-muted-foreground">
                                {tCapture('connectFirst')}
                            </p>
                        </div>
                    ) : captureLoading ? (
                        <div className="grid gap-2" role="status" aria-label={tCapture('loading')}>
                            <Skeleton className="h-5 w-40" />
                            <Skeleton className="h-14 w-full" />
                        </div>
                    ) : captureLoadError || !capture ? (
                        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                            <p className="text-xs text-destructive" role="alert">
                                {tCapture('loadFailed')}
                            </p>
                            <Button type="button" variant="outline" size="sm" onClick={onRetryCapture}>
                                {tCapture('retry')}
                            </Button>
                        </div>
                    ) : (
                        <div className="grid gap-4">
                            <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                                <div>
                                    <div className="flex flex-wrap items-center gap-2">
                                        <h3 className="text-sm font-medium text-foreground">{tCapture('title')}</h3>
                                        <Badge variant="outline">
                                            {tCapture(`state.${captureState(capture)}`)}
                                        </Badge>
                                    </div>
                                    <p className="mt-1 text-xs text-muted-foreground">
                                        {tCapture(`stateDescription.${captureState(capture)}`)}
                                    </p>
                                </div>
                                <div className="flex flex-wrap gap-2">
                                    <Button
                                        type="button"
                                        variant="outline"
                                        size="sm"
                                        disabled={busy}
                                        onClick={onConfigure}
                                    >
                                        <AdjustmentsHorizontalIcon className="size-4" />
                                        {capture.userPolicy.enabled
                                            ? tCapture('editPolicy')
                                            : tCapture('configure')}
                                    </Button>
                                    {capture.reviewCount > 0 || capture.pendingApprovalCount > 0 ? (
                                        <Button
                                            type="button"
                                            variant="outline"
                                            size="sm"
                                            disabled={busy}
                                            onClick={onReviews}
                                        >
                                            <UserGroupIcon className="size-4" />
                                            {tCapture('reviews', {
                                                count: capture.reviewCount + capture.pendingApprovalCount,
                                            })}
                                        </Button>
                                    ) : null}
                                    <Button
                                        type="button"
                                        size="sm"
                                        disabled={
                                            busy
                                            || !capture.effectivePolicy.enabled
                                            || connection.status !== 'connected'
                                        }
                                        onClick={needsReconnect ? onConnect : onSync}
                                    >
                                        <ArrowPathIcon className="size-4" />
                                        {needsReconnect ? t('reconnect') : tCapture('syncNow')}
                                    </Button>
                                </div>
                            </div>
                            <CaptureHealth streams={capture.streams} />
                        </div>
                    )}
                </div>
            ) : null}
        </article>
    );
}
