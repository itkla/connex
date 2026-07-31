'use client';

import { ChevronRightIcon, InboxIcon } from '@heroicons/react/24/outline';
import { useLocale, useTranslations } from 'next-intl';

import CaptureReviewDialog from '@/app/components/account/connected-capture/CaptureReviewDialog';
import type {
    CaptureReviewDecision,
    CaptureReviewItem,
    CaptureReviewPage,
    DuplicatePreflightResponse,
    PersonDuplicatePreflightRequest,
    ProviderCaptureOverview,
} from '@/app/lib/types';
import { formatDateTime } from '@/app/lib/utils';
import { Badge } from '@/components/ui/badge';
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
    ResponsiveDialogContent,
    ResponsiveDialogDescription,
    ResponsiveDialogHeader,
    ResponsiveDialogTitle,
} from '@/components/ui/responsive-dialog';
import { Skeleton } from '@/components/ui/skeleton';

/**
 * Presents the paged provider review queue as a desktop dialog or mobile drawer.
 */
export default function CaptureReviewQueue({
    overview,
    page,
    selected,
    open,
    loading,
    error,
    busy,
    canCreatePeople,
    onOpenChange,
    onPageChange,
    onSelect,
    onRetry,
    onDecide,
    onApprove,
    onPreflight,
}: {
    overview: ProviderCaptureOverview;
    page: CaptureReviewPage | null;
    selected: CaptureReviewItem | null;
    open: boolean;
    loading: boolean;
    error: boolean;
    busy: boolean;
    canCreatePeople: boolean;
    onOpenChange: (open: boolean) => void;
    onPageChange: (page: number) => void;
    onSelect: (review: CaptureReviewItem | null) => void;
    onRetry: () => void;
    onDecide: (review: CaptureReviewItem, decision: CaptureReviewDecision) => Promise<boolean>;
    onApprove: (review: CaptureReviewItem) => Promise<boolean>;
    onPreflight: (request: PersonDuplicatePreflightRequest) => Promise<DuplicatePreflightResponse>;
}) {
    const t = useTranslations('AccountCaptureReviews');
    const tConnections = useTranslations('AccountConnections');
    const locale = useLocale();
    const pageCount = page ? Math.max(1, Math.ceil(page.total / page.size)) : 1;

    return (
        <ResponsiveDialog open={open} onOpenChange={onOpenChange}>
            <ResponsiveDialogContent
                className="sm:max-w-2xl"
                showCloseButton={!busy}
            >
                <ResponsiveDialogHeader className="px-4 pt-4 sm:px-0 sm:pt-0">
                    <ResponsiveDialogTitle>
                        {t('title', { provider: tConnections(`provider_${overview.provider}`) })}
                    </ResponsiveDialogTitle>
                    <ResponsiveDialogDescription>
                        {t('description')}
                    </ResponsiveDialogDescription>
                </ResponsiveDialogHeader>

                <div className="px-4 py-4 sm:px-0">
                    {selected ? (
                        <CaptureReviewDialog
                            key={`${selected.id}-${selected.version}`}
                            review={selected}
                            busy={busy}
                            canCreatePeople={canCreatePeople}
                            onBack={() => onSelect(null)}
                            onDecide={(decision) => onDecide(selected, decision)}
                            onApprove={() => onApprove(selected)}
                            onPreflight={onPreflight}
                        />
                    ) : loading ? (
                        <div className="grid gap-2" role="status" aria-label={t('loading')}>
                            <Skeleton className="h-20 w-full" />
                            <Skeleton className="h-20 w-full" />
                            <Skeleton className="h-20 w-full" />
                        </div>
                    ) : error ? (
                        <div className="rounded-lg border border-border px-4 py-8 text-center">
                            <p className="text-sm text-muted-foreground" role="alert">
                                {t('loadFailed')}
                            </p>
                            <Button type="button" variant="outline" size="sm" className="mt-3" onClick={onRetry}>
                                {t('retry')}
                            </Button>
                        </div>
                    ) : !page || page.items.length === 0 ? (
                        <div className="rounded-lg border border-dashed border-border px-4 py-10 text-center">
                            <InboxIcon className="mx-auto size-6 text-muted-foreground" aria-hidden />
                            <p className="mt-3 text-sm font-medium text-foreground">{t('empty')}</p>
                            <p className="mt-1 text-xs text-muted-foreground">{t('emptyDescription')}</p>
                        </div>
                    ) : (
                        <div className="grid gap-4">
                            <ul className="grid gap-2" aria-label={t('queueLabel')}>
                                {page.items.map((review) => (
                                    <li key={review.id}>
                                        <button
                                            type="button"
                                            className="flex min-h-16 w-full items-center gap-3 rounded-lg border border-border px-3 py-2.5 text-left outline-none transition-colors hover:bg-muted/50 focus-visible:ring-2 focus-visible:ring-ring/50"
                                            onClick={() => onSelect(review)}
                                        >
                                            <div className="min-w-0 flex-1">
                                                <div className="flex flex-wrap items-center gap-1.5">
                                                    <Badge variant="outline">
                                                        {t(`stream.${review.stream}`)}
                                                    </Badge>
                                                    <span className="text-xs text-muted-foreground">
                                                        {t(`heldReason.${review.heldReason}`)}
                                                    </span>
                                                </div>
                                                <p className="mt-1 truncate text-sm font-medium text-foreground">
                                                    {review.subject
                                                        || review.displayName
                                                        || review.email
                                                        || t('untitled')}
                                                </p>
                                                <p className="mt-0.5 text-xs text-muted-foreground">
                                                    {formatDateTime(review.occurredAt, locale)}
                                                </p>
                                            </div>
                                            <ChevronRightIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                                        </button>
                                    </li>
                                ))}
                            </ul>

                            {pageCount > 1 ? (
                                <Pagination aria-label={t('paginationLabel')}>
                                    <PaginationContent>
                                        <PaginationItem>
                                            <PaginationPrevious
                                                aria-label={t('previousPage')}
                                                disabled={page.page <= 1}
                                                onClick={() => onPageChange(page.page - 1)}
                                            />
                                        </PaginationItem>
                                        <PaginationItem>
                                            <span className="px-3 text-xs tabular-nums text-muted-foreground">
                                                {t('pageStatus', {
                                                    page: page.page,
                                                    total: pageCount,
                                                })}
                                            </span>
                                        </PaginationItem>
                                        <PaginationItem>
                                            <PaginationNext
                                                aria-label={t('nextPage')}
                                                disabled={page.page >= pageCount}
                                                onClick={() => onPageChange(page.page + 1)}
                                            />
                                        </PaginationItem>
                                    </PaginationContent>
                                </Pagination>
                            ) : null}
                        </div>
                    )}
                </div>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}
