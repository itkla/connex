'use client';

import type { ReactNode } from 'react';
import {
    AdjustmentsHorizontalIcon,
    ArrowPathIcon,
    ChevronRightIcon,
    InboxIcon,
    PauseIcon,
    PlayIcon,
    ShieldCheckIcon,
    TrashIcon,
} from '@heroicons/react/24/outline';
import { useLocale, useTranslations } from 'next-intl';

import CaptureHealth from '@/app/components/account/connected-capture/CaptureHealth';
import { useLiveNow } from '@/app/hooks/useNow';
import { lastCaptureSuccessAt, needsReauthorization } from '@/app/lib/connectedCapture';
import type {
    ProviderCaptureOverview,
    ProviderConnection,
} from '@/app/lib/types';
import { formatRelativeTime } from '@/app/lib/utils';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
    Collapsible,
    CollapsibleContent,
    CollapsibleTrigger,
} from '@/components/ui/collapsible';
import {
    Drawer,
    DrawerContent,
    DrawerDescription,
    DrawerHeader,
    DrawerTitle,
} from '@/components/ui/drawer';
import { Skeleton } from '@/components/ui/skeleton';

/**
 * A titled band of the drawer. Sections are separated by a hairline rather than nested in cards,
 * so the drawer reads as one continuous record of the connection.
 */
function Section({ title, children }: { title: string; children: ReactNode }) {
    return (
        <section className="border-t border-border px-4 py-4 first:border-t-0 sm:px-5">
            <h3 className="text-xs font-medium tracking-wide text-muted-foreground uppercase">
                {title}
            </h3>
            <div className="mt-3">{children}</div>
        </section>
    );
}

/** A row that hands off to a focused surface, with an optional count worth acting on. */
function NavigationRow({
    label,
    count,
    icon,
    disabled,
    onSelect,
}: {
    label: string;
    count?: number;
    icon: ReactNode;
    disabled?: boolean;
    onSelect: () => void;
}) {
    return (
        <Button
            type="button"
            variant="ghost"
            size="dialog"
            className="w-full justify-start gap-3"
            disabled={disabled}
            onClick={onSelect}
        >
            {icon}
            <span className="min-w-0 flex-1 truncate text-left">{label}</span>
            {count != null && count > 0 ? <Badge variant="outline">{count}</Badge> : null}
            <ChevronRightIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
        </Button>
    );
}

/** One read-only line of the effective workspace policy. */
function PolicyLine({ label, value }: { label: string; value: string }) {
    return (
        <div className="flex items-baseline justify-between gap-4 py-1">
            <span className="text-sm text-muted-foreground">{label}</span>
            <span className="text-sm font-medium text-foreground">{value}</span>
        </div>
    );
}

/**
 * Everything about one connection that is not the card's at-a-glance answer.
 *
 * This is the journey's single "everything else" home, in the order the reader needs it: who is
 * connected and how it is running, what each stream is doing, the reader's own capture
 * preferences, the workspace defaults that bound them, the items waiting on the reader, and only
 * then the two ways to end the connection. Disconnect sits in the open because it is the ordinary
 * way to stop; erasing captured data sits behind its own disclosure because it destroys records
 * that disconnecting from this workspace alone would not.
 *
 * The workspace-defaults section follows the manage-gate rule: every reader sees the policy that
 * governs them, and only a reader who may change it is offered the control that changes it.
 *
 * @param canManageWorkspacePolicy whether the reader may edit the workspace defaults, not whether
 * they may see them
 */
export default function ManageConnectionDrawer({
    providerName,
    providerIcon,
    connection,
    capture,
    captureEnabled,
    captureLoading,
    captureLoadError,
    canManageWorkspacePolicy,
    busy,
    open,
    onOpenChange,
    onSync,
    onTogglePause,
    onReconnect,
    onEditPolicy,
    onWorkspacePolicy,
    onReviews,
    onDisconnect,
    onPurge,
    onRetryCapture,
}: {
    providerName: string;
    providerIcon: ReactNode;
    connection: ProviderConnection;
    capture: ProviderCaptureOverview | null;
    captureEnabled: boolean;
    captureLoading: boolean;
    captureLoadError: boolean;
    canManageWorkspacePolicy: boolean;
    busy: boolean;
    open: boolean;
    onOpenChange: (open: boolean) => void;
    onSync: () => void;
    onTogglePause: () => void;
    onReconnect: () => void;
    onEditPolicy: () => void;
    onWorkspacePolicy: () => void;
    onReviews: () => void;
    onDisconnect: () => void;
    onPurge: () => void;
    onRetryCapture: () => void;
}) {
    const t = useTranslations('AccountManageConnection');
    const tConnections = useTranslations('AccountConnections');
    const tCapture = useTranslations('AccountCaptureProvider');
    const tWorkspacePolicy = useTranslations('AccountWorkspaceCapturePolicy');
    const tReviews = useTranslations('AccountCaptureReviews');
    const locale = useLocale();
    const now = useLiveNow();
    const lastSuccess = lastCaptureSuccessAt(capture);
    const mustReauthorize = needsReauthorization(capture);
    const pendingReviews = capture
        ? capture.reviewCount + capture.pendingApprovalCount
        : 0;
    const paused = connection.status === 'paused';
    const workspacePolicy = capture?.workspacePolicy ?? null;
    const yesNo = (value: boolean) => t(value ? 'allowed' : 'notAllowed');

    return (
        <Drawer open={open} onOpenChange={onOpenChange} swipeDirection="right">
            <DrawerContent
                className="w-full sm:max-w-md"
                showCloseButton={!busy}
                aria-label={t('title', { provider: providerName })}
            >
                <DrawerHeader className="border-b border-border px-4 pr-14 sm:px-5">
                    <div className="flex items-center gap-3">
                        <div className="grid size-9 shrink-0 place-items-center rounded-lg bg-muted ring-1 ring-border">
                            {providerIcon}
                        </div>
                        <div className="min-w-0">
                            <DrawerTitle className="truncate text-sm font-semibold">
                                {t('title', { provider: providerName })}
                            </DrawerTitle>
                            <DrawerDescription className="truncate text-xs">
                                {connection.providerAccountEmail
                                    ?? tConnections('connectedNoEmail')}
                            </DrawerDescription>
                        </div>
                    </div>
                </DrawerHeader>

                <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
                    <Section title={t('sectionConnection')}>
                        <dl className="grid gap-2">
                            <div className="flex items-baseline justify-between gap-4">
                                <dt className="text-sm text-muted-foreground">
                                    {t('statusLabel')}
                                </dt>
                                <dd className="text-sm font-medium text-foreground">
                                    {tConnections(`status_${connection.status}`)}
                                </dd>
                            </div>
                            <div className="flex items-baseline justify-between gap-4">
                                <dt className="text-sm text-muted-foreground">
                                    {tConnections('lastSyncLabel')}
                                </dt>
                                <dd className="text-sm font-medium text-foreground">
                                    {lastSuccess
                                        ? formatRelativeTime(lastSuccess, locale, now)
                                        : tConnections('lastSyncNever')}
                                </dd>
                            </div>
                        </dl>
                        <div className="mt-3 flex flex-wrap gap-2">
                            {mustReauthorize ? (
                                <Button type="button" size="toolbar" disabled={busy} onClick={onReconnect}>
                                    <ArrowPathIcon data-icon="inline-start" />
                                    {tConnections('reconnect')}
                                </Button>
                            ) : (
                                <Button
                                    type="button"
                                    size="toolbar"
                                    disabled={
                                        busy
                                        || !captureEnabled
                                        || !capture?.effectivePolicy.enabled
                                        || connection.status !== 'connected'
                                    }
                                    onClick={onSync}
                                >
                                    <ArrowPathIcon data-icon="inline-start" />
                                    {tCapture('syncNow')}
                                </Button>
                            )}
                            {connection.status === 'connected' || paused ? (
                                <Button
                                    type="button"
                                    variant="outline"
                                    size="toolbar"
                                    disabled={busy}
                                    onClick={onTogglePause}
                                >
                                    {paused ? (
                                        <PlayIcon data-icon="inline-start" />
                                    ) : (
                                        <PauseIcon data-icon="inline-start" />
                                    )}
                                    {tConnections(paused ? 'resume' : 'pause')}
                                </Button>
                            ) : null}
                        </div>
                        {paused ? (
                            <p className="mt-2 text-xs text-muted-foreground">{t('pausedNote')}</p>
                        ) : null}
                    </Section>

                    {captureEnabled ? (
                        <Section title={t('sectionActivity')}>
                            {captureLoading ? (
                                <div className="grid gap-2" role="status" aria-label={tCapture('loading')}>
                                    <Skeleton className="h-12 w-full" />
                                    <Skeleton className="h-12 w-full" />
                                </div>
                            ) : captureLoadError || !capture ? (
                                <div className="flex flex-col gap-3">
                                    <p className="text-sm text-destructive" role="alert">
                                        {tCapture('loadFailed')}
                                    </p>
                                    <Button
                                        type="button"
                                        variant="outline"
                                        size="toolbar"
                                        className="self-start"
                                        onClick={onRetryCapture}
                                    >
                                        {tCapture('retry')}
                                    </Button>
                                </div>
                            ) : (
                                <CaptureHealth streams={capture.streams} />
                            )}
                        </Section>
                    ) : null}

                    {captureEnabled && capture ? (
                        <>
                            <Section title={t('sectionPreferences')}>
                                <p className="text-xs text-muted-foreground">
                                    {tCapture(
                                        `stateDescription.${capture.userPolicy.enabled ? 'configured' : 'notConfigured'}`,
                                    )}
                                </p>
                                <div className="mt-2 grid">
                                    <NavigationRow
                                        icon={<AdjustmentsHorizontalIcon className="size-5 shrink-0 text-muted-foreground" />}
                                        label={capture.userPolicy.enabled
                                            ? tCapture('editPolicy')
                                            : tCapture('configure')}
                                        disabled={busy}
                                        onSelect={onEditPolicy}
                                    />
                                    <NavigationRow
                                        icon={<InboxIcon className="size-5 shrink-0 text-muted-foreground" />}
                                        label={tReviews('drawerLabel')}
                                        count={pendingReviews}
                                        disabled={busy}
                                        onSelect={onReviews}
                                    />
                                </div>
                            </Section>

                            {workspacePolicy ? (
                                <Section title={t('sectionWorkspaceDefaults')}>
                                    <p className="text-xs text-muted-foreground">
                                        {t('workspaceDefaultsNote')}
                                    </p>
                                    <div className="mt-2 divide-y divide-border">
                                        <PolicyLine
                                            label={t('workspaceCaptureLabel')}
                                            value={yesNo(workspacePolicy.allowed)}
                                        />
                                        <PolicyLine
                                            label={t('workspaceBodiesLabel')}
                                            value={yesNo(workspacePolicy.bodyCaptureAllowed)}
                                        />
                                        <PolicyLine
                                            label={t('workspaceReviewLabel')}
                                            value={t(workspacePolicy.reviewRequired
                                                ? 'workspaceReviewRequired'
                                                : 'workspaceReviewOptional')}
                                        />
                                        <PolicyLine
                                            label={tWorkspacePolicy('maxBackfill')}
                                            value={t('days', {
                                                count: workspacePolicy.maxBackfillDays,
                                            })}
                                        />
                                    </div>
                                    {canManageWorkspacePolicy ? (
                                        <Button
                                            type="button"
                                            variant="outline"
                                            size="toolbar"
                                            className="mt-3"
                                            disabled={busy}
                                            onClick={onWorkspacePolicy}
                                        >
                                            <ShieldCheckIcon data-icon="inline-start" />
                                            {t('editWorkspaceDefaults')}
                                        </Button>
                                    ) : (
                                        <p className="mt-3 text-xs text-muted-foreground">
                                            {t('workspaceDefaultsAskAdmin')}
                                        </p>
                                    )}
                                </Section>
                            ) : null}
                        </>
                    ) : null}

                    <Section title={t('sectionEnding')}>
                        <Button
                            type="button"
                            variant="outline"
                            size="toolbar"
                            disabled={busy}
                            onClick={onDisconnect}
                        >
                            {tConnections('disconnect')}
                        </Button>
                        <p className="mt-2 text-xs text-muted-foreground">
                            {t('disconnectNote')}
                        </p>

                        {captureEnabled && capture ? (
                            <Collapsible className="mt-4">
                                <CollapsibleTrigger asChild>
                                    <Button type="button" variant="ghost" size="toolbar">
                                        {t('advanced')}
                                    </Button>
                                </CollapsibleTrigger>
                                <CollapsibleContent className="pt-3">
                                    <p className="text-xs text-muted-foreground">
                                        {t('purgeNote')}
                                    </p>
                                    <Button
                                        type="button"
                                        variant="destructive"
                                        size="toolbar"
                                        className="mt-3"
                                        disabled={busy}
                                        onClick={onPurge}
                                    >
                                        <TrashIcon data-icon="inline-start" />
                                        {tCapture('purge')}
                                    </Button>
                                </CollapsibleContent>
                            </Collapsible>
                        ) : null}
                    </Section>
                </div>
            </DrawerContent>
        </Drawer>
    );
}
