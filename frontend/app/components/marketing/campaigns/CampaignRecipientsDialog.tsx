'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { useLocale, useTranslations } from 'next-intl';
import { UsersIcon } from '@heroicons/react/24/outline';
import { Loader2Icon } from 'lucide-react';

import { EmptyState } from '@/app/components/EmptyState';
import {
    Pagination,
    PaginationContent,
    PaginationItem,
    PaginationNext,
    PaginationPrevious,
} from '@/components/ui/pagination';
import {
    ResponsiveDialog,
    ResponsiveDialogContent,
    ResponsiveDialogDescription,
    ResponsiveDialogHeader,
    ResponsiveDialogTitle,
} from '@/components/ui/responsive-dialog';
import { getCampaignRecipients } from '@/app/lib/api';
import { formatDateTime } from '@/app/lib/utils';
import { recipientFilterFor, type EngagementCounter } from '@/app/components/marketing/campaigns/recipientFilters';
import type { CampaignRecipient } from '@/app/lib/types';

const PAGE_SIZE = 25;

/** The recipient page as the dialog knows it, so an in-flight read never claims an empty roster. */
type RecipientRoster = {
    status: 'loading' | 'ready' | 'failed';
    items: CampaignRecipient[];
    total: number;
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
 */
export default function CampaignRecipientsDialog({
    campaignId,
    counter,
    counterLabel,
    open,
    onOpenChange,
}: {
    campaignId: number;
    counter: EngagementCounter;
    counterLabel: string;
    open: boolean;
    onOpenChange: (next: boolean) => void;
}) {
    const t = useTranslations('CampaignRecipients');
    const locale = useLocale();
    const [page, setPage] = useState(1);
    const [roster, setRoster] = useState<RecipientRoster>({ status: 'loading', items: [], total: 0 });

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

    return (
        <ResponsiveDialog open={open} onOpenChange={onOpenChange}>
            <ResponsiveDialogContent
                className="flex max-h-[85dvh] flex-col gap-0 overflow-hidden p-0 sm:max-w-2xl"
                scrollable={false}
            >
                <ResponsiveDialogHeader className="shrink-0 border-b border-border px-4 py-4 sm:px-6">
                    <ResponsiveDialogTitle>{t('title', { counter: counterLabel })}</ResponsiveDialogTitle>
                    <ResponsiveDialogDescription>
                        {t('description', { count: total.toLocaleString(locale) })}
                    </ResponsiveDialogDescription>
                </ResponsiveDialogHeader>

                <div className="min-h-0 flex-1 overflow-y-auto px-4 py-2 sm:px-6">
                    {status === 'loading' ? (
                        <div className="flex items-center justify-center gap-2 py-12 text-sm text-muted-foreground">
                            <Loader2Icon className="size-4 animate-spin" />
                            {t('loading')}
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
                                <li key={recipient.deliveryId} className="flex flex-wrap items-center gap-x-3 gap-y-1 py-3">
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
        </ResponsiveDialog>
    );
}
