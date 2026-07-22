'use client';

import { useCallback, useEffect, useState } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { PencilIcon, TrashIcon, EllipsisHorizontalIcon } from '@heroicons/react/24/outline';

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
import { deleteProduct, exportProductsCsv, getProducts } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { formatCurrency } from '@/app/lib/utils';
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

/** Workspace-scoped product/service catalog admin: searchable table with create/edit/delete. */
export default function ProductsBrowser({ products: initial }: { products: Product[] }) {
    const t = useTranslations('ProductsBrowser');
    const tf = useTranslations('Filters');
    const locale = useLocale();
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
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-[100rem] flex-col gap-8">
                <Rise>
                    <div className="flex items-center justify-between">
                        <h1 className="text-4xl font-extrabold">{t('title')}</h1>
                        <RecordsActions
                            entity="products"
                            onNew={() => setDialog({ mode: 'create' })}
                            newLabel={t('newButton')}
                            newAriaLabel={t('newButton')}
                            onExport={exportProducts}
                        />
                    </div>
                </Rise>

                <Rise delay={0.06}>
                    <div className="flex items-center justify-between gap-3">
                        <SectionHeader title={t('sectionCatalog')} />
                        <div className="w-64">
                            <SearchField value={query} onChange={setQuery} onClear={() => setQuery('')}
                                placeholder={t('searchPlaceholder')}
                                searchAria={tf('searchAria')} clearAria={tf('clearSearchAria')} />
                        </div>
                    </div>
                </Rise>

                <Rise delay={0.12}>
                    {searching ? (
                        <div role="status" aria-live="polite" className="rounded-2xl border border-border bg-card px-6 py-16 text-center text-sm text-muted-foreground">
                            {t('searching')}
                        </div>
                    ) : searchFailed ? (
                        <div role="status" aria-live="polite" className="flex flex-col items-center gap-3 rounded-2xl border border-border bg-card px-6 py-16 text-center text-sm text-muted-foreground">
                            <span>{t('searchFailed')}</span>
                            <Button variant="outline" size="sm" onClick={() => setSearchAttempt((attempt) => attempt + 1)}>
                                {t('retrySearch')}
                            </Button>
                        </div>
                    ) : filtered.length === 0 ? (
                        <div className="rounded-2xl border border-border bg-card px-6 py-16 text-center text-sm text-muted-foreground">
                            {query ? t('noMatches') : t('empty')}
                        </div>
                    ) : (
                        <div className="overflow-hidden rounded-2xl border border-border bg-card">
                            <table className="w-full text-sm">
                                <thead>
                                    <tr className="border-b border-border text-left text-xs uppercase tracking-[0.08em] text-muted-foreground">
                                        <th className="px-6 py-3 font-medium">{t('columnName')}</th>
                                        <th className="px-6 py-3 font-medium">{t('columnSku')}</th>
                                        <th className="px-6 py-3 font-medium text-right">{t('columnPrice')}</th>
                                        <th className="px-6 py-3 font-medium">{t('columnBilling')}</th>
                                        <th className="px-6 py-3 font-medium">{t('columnStatus')}</th>
                                        <th className="px-6 py-3" />
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-border">
                                    {filtered.map((p) => (
                                        <tr key={p.id} className="transition-colors hover:bg-muted/50">
                                            <td className="px-6 py-3">
                                                <div className="font-medium text-foreground">{p.name}</div>
                                                {p.unit ? <div className="text-xs text-muted-foreground">{t('perUnit', { unit: p.unit })}</div> : null}
                                            </td>
                                            <td className="px-6 py-3 text-muted-foreground">{p.sku || '—'}</td>
                                            <td className="px-6 py-3 text-right tabular-nums">{formatCurrency(p.unitPrice, p.currency, locale)}</td>
                                            <td className="px-6 py-3 text-muted-foreground">
                                                {p.billingFrequency === 'recurring' ? t('recurring') : t('oneTime')}
                                            </td>
                                            <td className="px-6 py-3">
                                                <span className={p.active ? 'text-chart-won' : 'text-muted-foreground'}>
                                                    {p.active ? t('active') : t('inactive')}
                                                </span>
                                            </td>
                                            <td className="px-6 py-3 text-right">
                                                <DropdownMenu>
                                                    <DropdownMenuTrigger asChild>
                                                        <Button variant="ghost" size="icon-xs" aria-label={t('actions')}>
                                                            <EllipsisHorizontalIcon className="size-4" />
                                                        </Button>
                                                    </DropdownMenuTrigger>
                                                    <DropdownMenuContent align="end">
                                                        <DropdownMenuItem onSelect={() => setDialog({ mode: 'edit', product: p })}>
                                                            <PencilIcon className="size-4" />{t('edit')}
                                                        </DropdownMenuItem>
                                                        <DropdownMenuSeparator />
                                                        <DropdownMenuItem variant="destructive" onSelect={() => setRemoveTarget(p)}>
                                                            <TrashIcon className="size-4" />{t('delete')}
                                                        </DropdownMenuItem>
                                                    </DropdownMenuContent>
                                                </DropdownMenu>
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    )}
                </Rise>
            </div>

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
        </div>
    );
}
