'use client';

import { useTranslations } from 'next-intl';
import { Pagination, PaginationContent, PaginationItem, PaginationPrevious, PaginationNext } from '@/components/ui/pagination';

/** Shared bounded-list progress and navigation, including filtered task board pages. */
export default function ActivityListPagination({ page, size, total, loading, onPageChange }: {
    page: number;
    size: number;
    total: number;
    loading: boolean;
    onPageChange: (page: number) => void;
}) {
    const t = useTranslations('ActivityList');
    return (
        <div className="flex flex-wrap items-center justify-between gap-3 py-3" aria-busy={loading}>
            <p className="text-sm text-muted-foreground" role="status">
                {loading ? t('loading') : t('range', { from: total ? (page - 1) * size + 1 : 0, to: Math.min(page * size, total), total })}
            </p>
            <Pagination className="mx-0 w-auto" aria-label={t('pagination')}>
                <PaginationContent>
                    <PaginationItem><PaginationPrevious aria-label={t('previous')} disabled={loading || page <= 1} onClick={() => onPageChange(page - 1)} /></PaginationItem>
                    <PaginationItem><PaginationNext aria-label={t('next')} disabled={loading || page * size >= total} onClick={() => onPageChange(page + 1)} /></PaginationItem>
                </PaginationContent>
            </Pagination>
        </div>
    );
}
