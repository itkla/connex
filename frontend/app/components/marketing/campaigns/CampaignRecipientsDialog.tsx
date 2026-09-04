'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { useLocale, useTranslations } from 'next-intl';
import { UsersIcon } from '@heroicons/react/24/outline';
import { Loader2Icon } from 'lucide-react';

import { EmptyState } from '@/app/components/EmptyState';
import { Button } from '@/components/ui/button';
import {
    Pagination,
    PaginationContent,
    PaginationItem,
    PaginationNext,
    PaginationPrevious,
} from '@/components/ui/pagination';
import {
    ResponsiveDialog,
    ResponsiveDialogClose,
    ResponsiveDialogContent,
    ResponsiveDialogDescription,
    ResponsiveDialogFooter,
    ResponsiveDialogHeader,
    ResponsiveDialogTitle,
} from '@/components/ui/responsive-dialog';
import { getCampaignRecipients, reconcileCampaignRecipient } from '@/app/lib/api';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { toastSuccess } from '@/app/lib/toast';
import { formatDateTime } from '@/app/lib/utils';
import { recipientFilterFor, type EngagementCounter } from '@/app/components/marketing/campaigns/recipientFilters';
import type {
    CampaignDeliveryFailureReason,
    CampaignDeliveryResolution,
    CampaignRecipient,
} from '@/app/lib/types';

const PAGE_SIZE = 25;

const FAILURE_REASON_KEY: Record<
    CampaignDeliveryFailureReason,
    `reasonCodes.${CampaignDeliveryFailureReason}`
> = {
    provider_timeout: 'reasonCodes.provider_timeout',
    provider_rejected: 'reasonCodes.provider_rejected',
    deadline_ambiguous: 'reasonCodes.deadline_ambiguous',
    delivery_target_changed: 'reasonCodes.delivery_target_changed',
    relay_error: 'reasonCodes.relay_error',
};

/**
 * What each counter's population actually is. "Reached" is only true of the counters that measure
 * arrival; a withheld send never reached anyone, and a bounce or a failure is an attempt that did
 * not land. One shared sentence across all eight would state the reverse of the truth for three of
 * them, which is exactly the claim this drill-through exists to make checkable.
 */
const DESCRIPTION_KEY: Record<EngagementCounter, string> = {
    recipients: 'descriptionRecipients',
    dispatched: 'descriptionDispatched',
    delivered: 'descriptionDelivered',
    bounced: 'descriptionBounced',
    complained: 'descriptionComplained',
    unsubscribed: 'descriptionUnsubscribed',
    skipped: 'descriptionSkipped',
    failed: 'descriptionFailed',
};

/** The recipient page as the dialog knows it, so an in-flight read never claims an empty roster. */
type RecipientRoster = {
    status: 'loading' | 'ready' | 'failed';
    items: CampaignRecipient[];
    total: number;
};

type PendingReconciliation = {
    deliveryId: number;
    resolution: CampaignDeliveryResolution;
};

/**
 * The contacts behind one campaign engagement count. A count is only ever a promise about people,
 * so this opens the population it measured and lands each row on the contact record it reached —
 * the delivery's own id is never the interesting thing.
 *
 * Rendered only for a caller with consent access, because the server guards a per-contact roster
 * that way; a caller without it sees plain counts and never this list.
 *
 * @param campaignId - the campaign whose deliveries to page through
 * @param counter - the counter that was opened, which selects the population
 * @param counterLabel - the counter's own label, so the title names what was clicked
 * @param canReconcile - whether the viewer has both permissions required to confirm an outcome
 */
export default function CampaignRecipientsDialog({
    campaignId,
    counter,
    counterLabel,
    canReconcile,
    open,
    onOpenChange,
}: {
    campaignId: number;
    counter: EngagementCounter;
    counterLabel: string;
    canReconcile: boolean;
    open: boolean;
    onOpenChange: (next: boolean) => void;
}) {
    const t = useTranslations('CampaignRecipients');
    const showApiError = useApiErrorToast('CampaignRecipients');
    const locale = useLocale();
    const [page, setPage] = useState(1);
    const [roster, setRoster] = useState<RecipientRoster>({ status: 'loading', items: [], total: 0 });
    const [pendingReconciliation, setPendingReconciliation] = useState<PendingReconciliation | null>(null);
    const [resolvingDeliveryId, setResolvingDeliveryId] = useState<number | null>(null);

    useEffect(() => {
        let active = true;
        getCampaignRecipients(campaignId, { ...recipientFilterFor(counter), page, size: PAGE_SIZE })
            .then((response) => {
                if (active) setRoster({ status: 'ready', items: response.items, total: response.total });
            })
            .catch(() => {
                if (active) setRoster({ status: 'failed', items: [], total: 0 });
            });
        return () => {
            active = false;
        };
    }, [campaignId, counter, page]);

    const { status, items, total } = roster;
    const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));
    const isConfirming = pendingReconciliation !== null
        && resolvingDeliveryId === pendingReconciliation.deliveryId;

    const confirmReconciliation = async () => {
        if (pendingReconciliation === null || !canReconcile) return;
        setResolvingDeliveryId(pendingReconciliation.deliveryId);
        try {
            const resolved = await reconcileCampaignRecipient(
                campaignId,
                pendingReconciliation.deliveryId,
                { resolution: pendingReconciliation.resolution },
            );
            setRoster((current) => {
                if (current.status !== 'ready') return current;
                const leavesFailedPopulation = counter === 'failed' && resolved.status !== 'failed';
                const nextItems = leavesFailedPopulation
                    ? current.items.filter((item) => item.deliveryId !== resolved.deliveryId)
                    : current.items.map((item) => item.deliveryId === resolved.deliveryId
                        ? {
                            ...item,
                            status: resolved.status,
                            reconciliationRequired: resolved.reconciliationRequired,
                            reasonCode: resolved.reasonCode,
                        }
                        : item);
                return {
                    status: 'ready',
                    items: nextItems,
                    total: leavesFailedPopulation ? Math.max(0, current.total - 1) : current.total,
                };
            });
            toastSuccess(pendingReconciliation.resolution === 'delivered'
                ? t('markedDelivered')
                : t('markedNotDelivered'));
            setPendingReconciliation(null);
        } catch (error) {
            showApiError(error, 'reconcileFailed');
        } finally {
            setResolvingDeliveryId(null);
        }
    };

    return (
        <ResponsiveDialog open={open} onOpenChange={onOpenChange}>
            <ResponsiveDialogContent
                className="flex max-h-[85dvh] flex-col gap-0 overflow-hidden p-0 sm:max-w-2xl"
                scrollable={false}
            >
                <ResponsiveDialogHeader className="shrink-0 border-b border-border px-4 py-4 sm:px-6">
                    <ResponsiveDialogTitle>{t('title', { counter: counterLabel })}</ResponsiveDialogTitle>
                    {status === 'ready' ? (
                        <ResponsiveDialogDescription>
                            {t(DESCRIPTION_KEY[counter], { count: total })}
                        </ResponsiveDialogDescription>
                    ) : status === 'loading' ? (
                        <ResponsiveDialogDescription>{t('loading')}</ResponsiveDialogDescription>
                    ) : null}
                </ResponsiveDialogHeader>

                <div className="min-h-0 flex-1 overflow-y-auto px-4 py-2 sm:px-6">
                    {status === 'loading' ? (
                        <div className="flex items-center justify-center py-12 text-muted-foreground">
                            <Loader2Icon className="size-5 animate-spin" aria-hidden />
                        </div>
                    ) : status === 'failed' ? (
                        <EmptyState
                            icon={UsersIcon}
                            title={t('failedTitle')}
                            body={t('failedBody')}
                            tone="muted"
                            variant="inline"
                        />
                    ) : items.length === 0 ? (
                        <EmptyState
                            icon={UsersIcon}
                            title={t('emptyTitle')}
                            body={t('emptyBody')}
                            tone="muted"
                            variant="inline"
                        />
                    ) : (
                        <ul className="divide-y divide-border">
                            {items.map((recipient) => (
                                <li key={recipient.deliveryId} className="py-3">
                                    <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                                        <span className="min-w-0 flex-1 truncate text-sm text-foreground">
                                            {recipient.personId != null ? (
                                                <Link
                                                    href={`/records/contacts/${recipient.personId}`}
                                                    className="font-medium underline-offset-2 hover:underline"
                                                >
                                                    {recipient.personLabel ?? t('contactUnnamed')}
                                                </Link>
                                            ) : (
                                                <span className="text-muted-foreground">{t('contactRemoved')}</span>
                                            )}
                                        </span>
                                        {recipient.updatedAt ?? recipient.createdAt ? (
                                            <span className="text-xs text-muted-foreground">
                                                {formatDateTime(recipient.updatedAt ?? recipient.createdAt, locale)}
                                            </span>
                                        ) : null}
                                    </div>
                                    {recipient.reconciliationRequired ? (
                                        <div className="mt-2 flex flex-col gap-2 rounded-lg border border-border bg-muted/40 p-3">
                                            <p className="text-xs text-warning-foreground">
                                                <span className="font-medium">{t('reconciliationRequired')}</span>
                                                {recipient.reasonCode ? (
                                                    <span className="ml-1 text-muted-foreground">
                                                        {t(FAILURE_REASON_KEY[recipient.reasonCode])}
                                                    </span>
                                                ) : null}
                                            </p>
                                            <div className="flex flex-wrap gap-2">
                                                <Button
                                                    type="button"
                                                    variant="outline"
                                                    size="inline"
                                                    disabled={!canReconcile || resolvingDeliveryId !== null}
                                                    onClick={() => setPendingReconciliation({
                                                        deliveryId: recipient.deliveryId,
                                                        resolution: 'delivered',
                                                    })}
                                                >
                                                    {t('markDelivered')}
                                                </Button>
                                                <Button
                                                    type="button"
                                                    variant="outline"
                                                    size="inline"
                                                    disabled={!canReconcile || resolvingDeliveryId !== null}
                                                    onClick={() => setPendingReconciliation({
                                                        deliveryId: recipient.deliveryId,
                                                        resolution: 'not_delivered',
                                                    })}
                                                >
                                                    {t('markNotDelivered')}
                                                </Button>
                                            </div>
                                            {!canReconcile ? (
                                                <p className="text-xs text-muted-foreground">
                                                    {t('reconciliationPermissionRequired')}
                                                </p>
                                            ) : null}
                                        </div>
                                    ) : null}
                                </li>
                            ))}
                        </ul>
                    )}
                </div>

                {pageCount > 1 ? (
                    <div className="shrink-0 border-t border-border px-4 py-3 sm:px-6">
                        <Pagination>
                            <PaginationContent>
                                <PaginationItem>
                                    <PaginationPrevious
                                        aria-label={t('previousPage')}
                                        disabled={page <= 1}
                                        onClick={() => setPage(Math.max(1, page - 1))}
                                    />
                                </PaginationItem>
                                <PaginationItem>
                                    <span className="px-3 text-sm text-muted-foreground">
                                        {t('page', { page, pageCount })}
                                    </span>
                                </PaginationItem>
                                <PaginationItem>
                                    <PaginationNext
                                        aria-label={t('nextPage')}
                                        disabled={page >= pageCount}
                                        onClick={() => setPage(Math.min(pageCount, page + 1))}
                                    />
                                </PaginationItem>
                            </PaginationContent>
                        </Pagination>
                    </div>
                ) : null}
            </ResponsiveDialogContent>
            <ResponsiveDialog
                open={pendingReconciliation !== null}
                onOpenChange={(next) => {
                    if (!next && !isConfirming) setPendingReconciliation(null);
                }}
            >
                <ResponsiveDialogContent className="sm:max-w-md" showCloseButton={!isConfirming}>
                    <ResponsiveDialogHeader className="px-4 pt-4 sm:px-0 sm:pt-0">
                        <ResponsiveDialogTitle>
                            {pendingReconciliation?.resolution === 'delivered'
                                ? t('deliveredConfirmTitle')
                                : t('notDeliveredConfirmTitle')}
                        </ResponsiveDialogTitle>
                        <ResponsiveDialogDescription>
                            {pendingReconciliation?.resolution === 'delivered'
                                ? t('deliveredConfirmDescription')
                                : t('notDeliveredConfirmDescription')}
                        </ResponsiveDialogDescription>
                    </ResponsiveDialogHeader>
                    <ResponsiveDialogFooter className="border-t border-border px-4 py-4 sm:border-0 sm:px-0 sm:py-0">
                        <ResponsiveDialogClose asChild>
                            <Button
                                type="button"
                                variant="outline"
                                size="dialog"
                                disabled={isConfirming}
                                onClick={() => setPendingReconciliation(null)}
                            >
                                {t('cancel')}
                            </Button>
                        </ResponsiveDialogClose>
                        <Button
                            type="button"
                            variant="destructive"
                            size="dialog"
                            disabled={isConfirming}
                            onClick={() => void confirmReconciliation()}
                        >
                            {isConfirming ? <Loader2Icon className="size-4 animate-spin" /> : null}
                            {isConfirming
                                ? t('confirming')
                                : pendingReconciliation?.resolution === 'delivered'
                                    ? t('confirmDelivered')
                                    : t('confirmNotDelivered')}
                        </Button>
                    </ResponsiveDialogFooter>
                </ResponsiveDialogContent>
            </ResponsiveDialog>
        </ResponsiveDialog>
    );
}
