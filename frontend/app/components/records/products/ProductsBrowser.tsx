'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import {
    CubeIcon,
    EllipsisHorizontalIcon,
    MagnifyingGlassIcon,
    PencilIcon,
    TrashIcon,
} from '@heroicons/react/24/outline';

import { Button } from '@/components/ui/button';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import Rise from '@/app/components/motion/Rise';
import SectionHeader from '@/app/components/dashboard/SectionHeader';
import { SearchField } from '@/app/components/filters';
import RecordsActions from '@/app/components/import/RecordsActions';
import DeleteRecordDialog from '@/app/components/records/DeleteRecordDialog';
import ProductDialog from '@/app/components/records/products/ProductDialog';
import { EmptyState } from '@/app/components/EmptyState';
import { PageHeader } from '@/app/components/PageHeader';
import { PageShell } from '@/app/components/PageShell';
import { Skeleton } from '@/components/ui/skeleton';
import { deleteProduct, exportProductsCsv, getProducts } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { formatCurrency, formatDate } from '@/app/lib/utils';
import type { Product } from '@/app/lib/types';
import { useWorkspace } from '@/app/hooks/useWorkspace';

type ProductSearchScope = {
    query: string;
    revision: number;
    attempt: number;
    workspaceId: number;
};

type ProductSearchResult = ProductSearchScope & (
    | { status: 'success'; products: Product[] }
    | { status: 'error' }
);

/** Workspace-scoped product/service catalog admin with responsive browsing and create/edit/delete. */
export default function ProductsBrowser({ products: initial }: { products: Product[] }) {
    const t = useTranslations('ProductsBrowser');
    const layoutT = useTranslations('RecordsProductsLayout');
    const tf = useTranslations('Filters');
    const locale = useLocale();
    const numberFormatter = useMemo(() => new Intl.NumberFormat(locale), [locale]);
    const { activeWorkspaceId, switching } = useWorkspace();
    const [products, setProducts] = useState(initial);
    const [query, setQuery] = useState('');
    const [dialog, setDialog] = useState<{ mode: 'create' | 'edit'; product?: Product } | null>(null);
    const [removeTarget, setRemoveTarget] = useState<Product | null>(null);
    const [isRemoving, setIsRemoving] = useState(false);
    const [catalogRevision, setCatalogRevision] = useState(0);
    const [searchAttempt, setSearchAttempt] = useState(0);
    const [searchResult, setSearchResult] = useState<ProductSearchResult | null>(null);
    const normalizedQuery = query.trim();

    useEffect(() => {
        if (!normalizedQuery || activeWorkspaceId === null || switching) return;
        const controller = new AbortController();
        const timeout = window.setTimeout(() => {
            getProducts(
                { q: normalizedQuery },
                { signal: controller.signal, headers: { 'X-Workspace-Id': String(activeWorkspaceId) } },
            )
                .then((matches) => setSearchResult({
                    query: normalizedQuery,
                    revision: catalogRevision,
                    attempt: searchAttempt,
                    workspaceId: activeWorkspaceId,
                    status: 'success',
                    products: matches,
                }))
                .catch((error: unknown) => {
                    if (controller.signal.aborted || (error instanceof Error && error.name === 'AbortError')) return;
                    setSearchResult({
                        query: normalizedQuery,
                        revision: catalogRevision,
                        attempt: searchAttempt,
                        workspaceId: activeWorkspaceId,
                        status: 'error',
                    });
                });
        }, 200);
        return () => {
            window.clearTimeout(timeout);
            controller.abort();
        };
    }, [activeWorkspaceId, catalogRevision, normalizedQuery, searchAttempt, switching]);

    const searchCurrent = !switching
        && searchResult?.query === normalizedQuery
        && searchResult.revision === catalogRevision
        && searchResult.attempt === searchAttempt
        && searchResult.workspaceId === activeWorkspaceId;
    let filtered = products;
    if (normalizedQuery) {
        filtered = searchCurrent && searchResult.status === 'success' ? searchResult.products : [];
    }
    const searching = normalizedQuery.length > 0 && !searchCurrent;
    const searchFailed = normalizedQuery.length > 0 && searchCurrent && searchResult.status === 'error';

    const upsert = (saved: Product) => {
        setProducts((prev) => prev.some((p) => p.id === saved.id)
            ? prev.map((p) => (p.id === saved.id ? saved : p))
            : [...prev, saved].sort((a, b) => a.name.localeCompare(b.name)));
        setCatalogRevision((revision) => revision + 1);
    };

    const exportProducts = useCallback(
        (signal: AbortSignal, workspaceId: number) => exportProductsCsv(
            { q: query.trim() || undefined },
            { signal, headers: { 'X-Workspace-Id': String(workspaceId) } },
        ),
        [query],
    );

    const confirmRemove = async () => {
        if (!removeTarget) return;
        setIsRemoving(true);
        try {
            await deleteProduct(removeTarget.id);
            setProducts((prev) => prev.filter((p) => p.id !== removeTarget.id));
            setCatalogRevision((revision) => revision + 1);
            toastSuccess(t('deleted'));
            setRemoveTarget(null);
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('deleteFailed'));
        } finally {
            setIsRemoving(false);
        }
    };

    return (
        <>
            <PageShell tier="wide">
                <Rise>
                    <PageHeader
                        title={t('title')}
                        description={layoutT('description')}
                        actions={
                            <RecordsActions
                                entity="products"
                                onNew={() => setDialog({ mode: 'create' })}
                                newLabel={t('newButton')}
                                newAriaLabel={t('newButton')}
                                onExport={exportProducts}
                            />
                        }
                    />
                </Rise>

                <Rise delay={0.06}>
                    <div className="space-y-3">
                        <SectionHeader
                            title={t('sectionCatalog')}
                            action={searching || searchFailed ? undefined : (
                                <span className="text-xs tabular-nums text-muted-foreground">
                                    {t('catalogCount', { count: filtered.length })}
                                </span>
                            )}
                        />
                        <div className="w-full sm:max-w-sm">
                            <SearchField
                                value={query}
                                onChange={setQuery}
                                onClear={() => setQuery('')}
                                placeholder={t('searchPlaceholder')}
                                searchAria={tf('searchAria')}
                                clearAria={tf('clearSearchAria')}
                            />
                        </div>
                    </div>
                </Rise>

                <Rise delay={0.12}>
                    {searching ? (
                        <div className="space-y-2 rounded-2xl border border-border bg-card p-4" aria-busy>
                            {Array.from({ length: 6 }, (_, row) => (
                                <Skeleton key={row} className="h-9 w-full rounded-lg" />
                            ))}
                        </div>
                    ) : searchFailed ? (
                        <div role="status" aria-live="polite" className="flex flex-col items-center gap-3 rounded-2xl border border-border bg-card px-6 py-16 text-center text-sm text-muted-foreground">
                            <span>{t('searchFailed')}</span>
                            <Button variant="outline" size="sm" onClick={() => setSearchAttempt((attempt) => attempt + 1)}>
                                {t('retrySearch')}
                            </Button>
                        </div>
                    ) : products.length === 0 && normalizedQuery.length === 0 ? (
                        <EmptyState
                            icon={CubeIcon}
                            title={t('emptyTitle')}
                            body={t('empty')}
                            action={
                                <Button variant="brand" onClick={() => setDialog({ mode: 'create' })}>
                                    {t('newButton')}
                                </Button>
                            }
                        />
                    ) : filtered.length === 0 ? (
                        <EmptyState
                            icon={MagnifyingGlassIcon}
                            title={t('noMatchesTitle')}
                            body={t('noMatches')}
                            tone="muted"
                            action={
                                <Button
                                    variant="outline"
                                    onClick={() => setQuery('')}
                                >
                                    {t('clearFilters')}
                                </Button>
                            }
                        />
                    ) : (
                        <div className="overflow-hidden rounded-2xl border border-border bg-card">
                            <div
                                aria-hidden="true"
                                className="hidden grid-cols-[minmax(12rem,1fr)_6rem_7rem_6rem_5rem_10rem_2rem] items-center gap-5 border-b border-border px-5 py-3 text-left text-xs uppercase tracking-[0.08em] text-muted-foreground xl:grid"
                            >
                                <span>{t('columnName')}</span>
                                <span>{t('columnSku')}</span>
                                <span className="text-right">{t('columnPrice')}</span>
                                <span>{t('columnBilling')}</span>
                                <span>{t('columnTax')}</span>
                                <span>{t('columnAvailability')}</span>
                                <span />
                            </div>
                            <ul className="divide-y divide-border">
                                {filtered.map((product) => (
                                    <li
                                        key={product.id}
                                        className="grid grid-cols-[minmax(0,1fr)_auto_auto] items-start gap-x-3 p-4 transition-colors hover:bg-muted/50 xl:grid-cols-[minmax(12rem,1fr)_6rem_7rem_6rem_5rem_10rem_2rem] xl:items-center xl:gap-5 xl:px-5 xl:py-3.5"
                                    >
                                        <div className="min-w-0 xl:col-start-1 xl:row-start-1">
                                            <div className="truncate font-medium text-foreground">{product.name}</div>
                                            {product.description ? (
                                                <p className="mt-1 line-clamp-2 text-sm text-muted-foreground xl:mt-0.5 xl:truncate xl:text-xs">
                                                    {product.description}
                                                </p>
                                            ) : product.unit ? (
                                                <p className="mt-0.5 text-xs text-muted-foreground">{t('perUnit', { unit: product.unit })}</p>
                                            ) : null}
                                        </div>
                                        <div className="col-start-2 row-start-1 xl:col-start-6 xl:row-start-1">
                                            <span className="sr-only">{t('columnAvailability')}: </span>
                                            <ProductStatus active={product.active} activeLabel={t('active')} inactiveLabel={t('inactive')} />
                                            <div className="mt-1 hidden text-xs text-muted-foreground xl:block">
                                                <span className="sr-only">{t('effectiveDates')}: </span>
                                                {formatEffectiveRange(product, locale, t)}
                                            </div>
                                        </div>
                                        <div className="col-start-3 row-start-1 text-right xl:col-start-7 xl:row-start-1">
                                            <ProductActions
                                                label={t('actionsFor', { name: product.name })}
                                                editLabel={t('edit')}
                                                deleteLabel={t('delete')}
                                                onEdit={() => setDialog({ mode: 'edit', product })}
                                                onDelete={() => setRemoveTarget(product)}
                                            />
                                        </div>
                                        <div className="col-span-3 mt-4 xl:col-span-1 xl:col-start-3 xl:row-start-1 xl:mt-0 xl:text-right">
                                            <div className="text-lg font-semibold tabular-nums text-foreground xl:text-sm">
                                                <span className="sr-only">{t('columnPrice')}: </span>
                                                {formatCurrency(product.unitPrice, product.currency, locale)}
                                            </div>
                                            <div className="text-xs text-muted-foreground xl:hidden">
                                                {product.billingFrequency === 'recurring' ? t('recurring') : t('oneTime')}
                                                {product.unit ? ` · ${t('perUnit', { unit: product.unit })}` : ''}
                                            </div>
                                        </div>
                                        <div className="hidden text-sm text-muted-foreground xl:col-start-2 xl:row-start-1 xl:block">
                                            <span className="sr-only">{t('columnSku')}: </span>
                                            {product.sku || '—'}
                                        </div>
                                        <div className="hidden text-sm text-muted-foreground xl:col-start-4 xl:row-start-1 xl:block">
                                            <span className="sr-only">{t('columnBilling')}: </span>
                                            {product.billingFrequency === 'recurring' ? t('recurring') : t('oneTime')}
                                        </div>
                                        <div className="hidden text-sm text-muted-foreground xl:col-start-5 xl:row-start-1 xl:block">
                                            <span className="sr-only">{t('columnTax')}: </span>
                                            {formatTaxRate(product, numberFormatter)}
                                        </div>
                                        <dl className="col-span-3 mt-4 grid grid-cols-2 gap-x-4 gap-y-3 border-t border-border pt-3 text-xs xl:hidden">
                                            <div>
                                                <dt className="text-muted-foreground">{t('columnSku')}</dt>
                                                <dd className="mt-0.5 truncate font-medium text-foreground">{product.sku || '—'}</dd>
                                            </div>
                                            <div>
                                                <dt className="text-muted-foreground">{t('columnTax')}</dt>
                                                <dd className="mt-0.5 font-medium text-foreground">{formatTaxRate(product, numberFormatter)}</dd>
                                            </div>
                                            <div className="col-span-2">
                                                <dt className="text-muted-foreground">{t('effectiveDates')}</dt>
                                                <dd className="mt-0.5 font-medium text-foreground">{formatEffectiveRange(product, locale, t)}</dd>
                                            </div>
                                        </dl>
                                    </li>
                                ))}
                            </ul>
                        </div>
                    )}
                </Rise>
            </PageShell>

            {dialog && (
                <ProductDialog
                    key={dialog.mode === 'edit' ? `edit-${dialog.product?.id}` : 'create'}
                    open
                    onOpenChange={(next) => { if (!next) setDialog(null); }}
                    mode={dialog.mode}
                    product={dialog.product}
                    onSaved={upsert}
                />
            )}

            <DeleteRecordDialog
                open={removeTarget !== null}
                onOpenChange={(next) => { if (!next) setRemoveTarget(null); }}
                selectedIds={removeTarget ? new Set([removeTarget.id]) : new Set()}
                selectedItems={removeTarget ? [removeTarget] : []}
                entityLabel={t('entityLabel')}
                getDisplayName={(p) => p.name}
                isDeleting={isRemoving}
                confirmDelete={confirmRemove}
            />
        </>
    );
}

function ProductStatus({ active, activeLabel, inactiveLabel }: { active: boolean; activeLabel: string; inactiveLabel: string }) {
    return (
        <span className={active
            ? 'inline-flex shrink-0 items-center gap-1.5 rounded-full bg-chart-won/10 px-2 py-1 text-xs font-medium text-chart-won'
            : 'inline-flex shrink-0 items-center gap-1.5 rounded-full bg-muted px-2 py-1 text-xs font-medium text-muted-foreground'}>
            <span aria-hidden="true" className="size-1.5 rounded-full bg-current" />
            {active ? activeLabel : inactiveLabel}
        </span>
    );
}

function ProductActions({
    label,
    editLabel,
    deleteLabel,
    onEdit,
    onDelete,
}: {
    label: string;
    editLabel: string;
    deleteLabel: string;
    onEdit: () => void;
    onDelete: () => void;
}) {
    return (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <Button variant="ghost" size="icon-xs" aria-label={label} className="size-10 xl:size-6">
                    <EllipsisHorizontalIcon className="size-4" />
                </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
                <DropdownMenuItem onSelect={onEdit}>
                    <PencilIcon className="size-4" />{editLabel}
                </DropdownMenuItem>
                <DropdownMenuSeparator />
                <DropdownMenuItem variant="destructive" onSelect={onDelete}>
                    <TrashIcon className="size-4" />{deleteLabel}
                </DropdownMenuItem>
            </DropdownMenuContent>
        </DropdownMenu>
    );
}

function formatTaxRate(product: Product, numberFormatter: Intl.NumberFormat): string {
    return product.taxRate == null ? '—' : `${numberFormatter.format(product.taxRate)}%`;
}

function formatEffectiveRange(product: Product, locale: string, t: ReturnType<typeof useTranslations>): string {
    const start = product.effectiveStart ? formatDate(product.effectiveStart, locale) : null;
    const end = product.effectiveEnd ? formatDate(product.effectiveEnd, locale) : null;
    if (start && end) return t('effectiveRange', { start, end });
    if (start) return t('effectiveFrom', { date: start });
    if (end) return t('effectiveUntil', { date: end });
    return t('noDateLimit');
}
