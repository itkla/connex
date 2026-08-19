'use client';

import { useCallback, useMemo, useState } from 'react';
import Link from 'next/link';
import { useLocale, useTranslations } from 'next-intl';
import { useReducedMotion } from 'motion/react';
import { DocumentCheckIcon } from '@heroicons/react/24/outline';

import Rise from '@/app/components/motion/Rise';
import { EmptyState } from '@/app/components/EmptyState';
import { FilterBar, MultiSelectFilter, SearchField, type FilterChipData } from '@/app/components/filters';
import { dealDocumentsHref } from '@/app/components/records/deals/dealLinks';
import {
    Pagination,
    PaginationContent,
    PaginationItem,
    PaginationNext,
    PaginationPrevious,
} from '@/components/ui/pagination';
import { Skeleton } from '@/components/ui/skeleton';
import { useServerRecords } from '@/app/hooks/useServerRecords';
import { getGeneratedDocuments } from '@/app/lib/api';
import { formatDate } from '@/app/lib/utils';
import type {
    DocumentStatus,
    DocumentType,
    GeneratedDocumentSummary,
    GeneratedDocumentsPageParams,
    User,
} from '@/app/lib/types';

const PAGE_SIZE = 25;

const STATUSES: DocumentStatus[] = ['draft', 'pending_approval', 'approved', 'final', 'superseded'];
const TYPES: DocumentType[] = ['quote', 'proposal', 'order_form', 'contract'];

const STATUS_KEY: Record<DocumentStatus, string> = {
    draft: 'statusDraft',
    pending_approval: 'statusPendingApproval',
    approved: 'statusApproved',
    final: 'statusFinal',
    superseded: 'statusSuperseded',
};

const TYPE_KEY: Record<DocumentType, string> = {
    quote: 'typeQuote',
    proposal: 'typeProposal',
    order_form: 'typeOrderForm',
    contract: 'typeContract',
};

const STATUS_CLASS: Record<DocumentStatus, string> = {
    draft: 'text-muted-foreground',
    pending_approval: 'text-chart-3',
    approved: 'text-chart-won',
    final: 'text-chart-won',
    superseded: 'text-muted-foreground',
};

function toggle(values: Set<string>, value: string): Set<string> {
    const next = new Set(values);
    if (next.has(value)) next.delete(value);
    else next.add(value);
    return next;
}

/**
 * The workspace-wide index of generated commercial documents. A document is authored, approved, and
 * superseded inside its parent deal, so every row leads back to that deal's documents section rather
 * than to a page of its own — this surface exists so a finished quote is findable without already
 * knowing which deal produced it.
 *
 * @param owners - workspace members, used to name the deal owner a document is scoped by
 */
export default function GeneratedDocumentsBrowser({ owners }: { owners: User[] }) {
    const t = useTranslations('GeneratedDocuments');
    const tf = useTranslations('Filters');
    const locale = useLocale();
    const reduce = useReducedMotion() ?? false;

    const [statuses, setStatuses] = useState<Set<string>>(new Set());
    const [types, setTypes] = useState<Set<string>>(new Set());

    const fetchDocuments = useCallback(
        ({ page, size, q, status, type }: GeneratedDocumentsPageParams) =>
            getGeneratedDocuments({ page, size, q, status, type }),
        [],
    );

    const extraParams = useMemo(
        () => ({
            status: statuses.size > 0 ? ([...statuses] as DocumentStatus[]) : undefined,
            type: types.size > 0 ? ([...types] as DocumentType[]) : undefined,
        }),
        [statuses, types],
    );

    const { items, total, loading, page, setPage, query, setQuery } = useServerRecords<
        GeneratedDocumentSummary,
        GeneratedDocumentsPageParams
    >(fetchDocuments, extraParams, { defaultSize: PAGE_SIZE });

    const ownerNames = useMemo(
        () => new Map(owners.map((owner) => [owner.id, owner.displayName])),
        [owners],
    );

    const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));
    const filtersActive = statuses.size > 0 || types.size > 0 || query.trim().length > 0;

    const clearFilters = () => {
        setStatuses(new Set());
        setTypes(new Set());
        setQuery('');
    };

    const chips: FilterChipData[] = useMemo(
        () => [
            ...[...statuses].map((status) => ({
                id: `status-${status}`,
                label: t(STATUS_KEY[status as DocumentStatus]),
                onRemove: () => setStatuses((prev) => toggle(prev, status)),
            })),
            ...[...types].map((type) => ({
                id: `type-${type}`,
                label: t(TYPE_KEY[type as DocumentType]),
                onRemove: () => setTypes((prev) => toggle(prev, type)),
            })),
        ],
        [statuses, types, t],
    );

    return (
        <>
            <Rise delay={0.06}>
                <FilterBar
                    reduce={reduce}
                    chips={chips}
                    hasActiveFilters={filtersActive}
                    onClearAll={clearFilters}
                    clearAllLabel={tf('clearAll')}
                    search={
                        <SearchField
                            value={query}
                            onChange={setQuery}
                            onClear={() => setQuery('')}
                            placeholder={t('searchPlaceholder')}
                            searchAria={tf('searchAria')}
                            clearAria={tf('clearSearchAria')}
                        />
                    }
                >
                    <MultiSelectFilter
                        label={t('statusLabel')}
                        ariaLabel={t('statusLabel')}
                        options={STATUSES.map((status) => ({ value: status, label: t(STATUS_KEY[status]) }))}
                        selected={statuses}
                        onToggle={(value) => setStatuses((prev) => toggle(prev, value))}
                        onClear={() => setStatuses(new Set())}
                        clearLabel={tf('clearAll')}
                    />
                    <MultiSelectFilter
                        label={t('typeLabel')}
                        ariaLabel={t('typeLabel')}
                        options={TYPES.map((type) => ({ value: type, label: t(TYPE_KEY[type]) }))}
                        selected={types}
                        onToggle={(value) => setTypes((prev) => toggle(prev, value))}
                        onClear={() => setTypes(new Set())}
                        clearLabel={tf('clearAll')}
                    />
                </FilterBar>
            </Rise>

            <Rise delay={0.12}>
                {loading && items.length === 0 ? (
                    <div className="overflow-hidden rounded-2xl border border-border bg-card">
                        <div className="divide-y divide-border">
                            {Array.from({ length: 6 }).map((_, row) => (
                                <div key={row} className="flex items-center gap-6 px-4 py-3 sm:px-6">
                                    <div className="min-w-0 flex-1 space-y-1.5">
                                        <Skeleton className="h-4 w-48 max-w-full" />
                                        <Skeleton className="h-3 w-32" />
                                    </div>
                                    <Skeleton className="hidden h-4 w-24 md:block" />
                                    <Skeleton className="hidden h-4 w-20 sm:block" />
                                    <Skeleton className="h-4 w-16" />
                                </div>
                            ))}
                        </div>
                    </div>
                ) : items.length === 0 ? (
                    <EmptyState
                        icon={DocumentCheckIcon}
                        title={filtersActive ? t('noMatchesTitle') : t('emptyTitle')}
                        body={filtersActive ? t('noMatchesBody') : t('emptyBody')}
                        tone={filtersActive ? 'muted' : 'brand'}
                    />
                ) : (
                    <div className="overflow-hidden rounded-2xl border border-border bg-card" aria-busy={loading}>
                        <table className="w-full text-sm">
                            <thead>
                                <tr className="border-b border-border text-left text-xs uppercase tracking-[0.08em] text-muted-foreground">
                                    <th className="px-4 py-3 font-medium sm:px-6">{t('columnDocument')}</th>
                                    <th className="hidden px-4 py-3 font-medium sm:px-6 md:table-cell">{t('columnDeal')}</th>
                                    <th className="px-4 py-3 font-medium sm:px-6">{t('columnStatus')}</th>
                                    <th className="hidden px-4 py-3 font-medium sm:table-cell sm:px-6">{t('columnOwner')}</th>
                                    <th className="hidden px-4 py-3 font-medium sm:px-6 lg:table-cell">{t('columnGenerated')}</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {items.map((document) => (
                                    <tr key={document.id} className="transition-colors hover:bg-muted/50">
                                        <td className="px-4 py-3 sm:px-6">
                                            <Link
                                                href={dealDocumentsHref(document.dealId)}
                                                className="block font-medium text-foreground underline-offset-2 hover:underline"
                                            >
                                                {document.title || t(TYPE_KEY[document.type])}
                                            </Link>
                                            <div className="text-xs text-muted-foreground">
                                                {t('versionLabel', { version: document.version })}
                                                {' · '}
                                                {t(TYPE_KEY[document.type])}
                                            </div>
                                        </td>
                                        <td className="hidden px-4 py-3 sm:px-6 md:table-cell">
                                            <Link
                                                href={dealDocumentsHref(document.dealId)}
                                                className="text-muted-foreground underline-offset-2 hover:text-brand hover:underline"
                                            >
                                                {document.dealName ?? t('dealUnavailable')}
                                            </Link>
                                        </td>
                                        <td className={`px-4 py-3 sm:px-6 ${STATUS_CLASS[document.status]}`}>
                                            {t(STATUS_KEY[document.status])}
                                        </td>
                                        <td className="hidden px-4 py-3 text-muted-foreground sm:table-cell sm:px-6">
                                            {document.dealOwnerId != null
                                                ? ownerNames.get(document.dealOwnerId) ?? t('ownerUnknown')
                                                : t('ownerUnassigned')}
                                        </td>
                                        <td className="hidden px-4 py-3 text-muted-foreground sm:px-6 lg:table-cell">
                                            {formatDate(document.generatedAt, locale)}
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}
            </Rise>

            {pageCount > 1 ? (
                <Rise delay={0.18}>
                    <Pagination>
                        <PaginationContent>
                            <PaginationItem>
                                <PaginationPrevious
                                    disabled={page <= 1}
                                    aria-label={t('previousPage')}
                                    onClick={() => setPage(Math.max(1, page - 1))}
                                />
                            </PaginationItem>
                            <PaginationItem>
                                <span className="px-3 text-sm text-muted-foreground">{t('page', { page, pageCount })}</span>
                            </PaginationItem>
                            <PaginationItem>
                                <PaginationNext
                                    disabled={page >= pageCount}
                                    aria-label={t('nextPage')}
                                    onClick={() => setPage(Math.min(pageCount, page + 1))}
                                />
                            </PaginationItem>
                        </PaginationContent>
                    </Pagination>
                </Rise>
            ) : null}
        </>
    );
}
