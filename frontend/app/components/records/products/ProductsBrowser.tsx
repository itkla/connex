'use client';

import { useCallback, useMemo, useState } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { useReducedMotion } from 'motion/react';
import { CubeIcon, PencilIcon, Squares2X2Icon, TableCellsIcon, TrashIcon } from '@heroicons/react/24/outline';

import { Button } from '@/components/ui/button';
import { ButtonGroup } from '@/components/ui/button-group';
import { SegmentedControl } from '@/components/ui/segmented-control';
import Rise from '@/app/components/motion/Rise';
import { FilterBar, SearchField, type FilterChipData } from '@/app/components/filters';
import RecordsActions from '@/app/components/import/RecordsActions';
import RecordsRenderView from '@/app/components/records/RecordsRenderView';
import RecordsFilterPills from '@/app/components/records/RecordsFilterPills';
import RecordsFilterSheet from '@/app/components/records/RecordsFilterSheet';
import RecordsSortMenu from '@/app/components/records/RecordsSortMenu';
import ColumnVisibilityMenu from '@/app/components/records/ColumnVisibilityMenu';
import DensityToggle from '@/app/components/records/DensityToggle';
import DeleteRecordDialog from '@/app/components/records/DeleteRecordDialog';
import ProductAvailability from '@/app/components/records/products/ProductAvailability';
import ProductCard from '@/app/components/records/products/ProductCard';
import ProductListRow from '@/app/components/records/products/ProductListRow';
import ProductDialog from '@/app/components/records/products/ProductDialog';
import { formatEffectiveRange, formatTaxRate } from '@/app/components/records/products/productDisplay';
import {
    applyRecordFilters,
    countActiveFilters,
    deriveFilterOptions,
    facetChips,
    type ColumnDef,
} from '@/app/components/records/types';
import { EmptyState } from '@/app/components/EmptyState';
import { PageHeader } from '@/app/components/PageHeader';
import { PageShell } from '@/app/components/PageShell';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { useColumnVisibility } from '@/app/hooks/useColumnVisibility';
import { useRecordDensity } from '@/app/hooks/useRecordDensity';
import { useRecordsBrowser } from '@/app/hooks/useRecordsBrowser';
import { useRecordsSort } from '@/app/hooks/useRecordsSort';
import { deleteProduct, exportProductsCsv } from '@/app/lib/api';
import { toastSuccess } from '@/app/lib/toast';
import { formatCurrency } from '@/app/lib/utils';
import type { Product } from '@/app/lib/types';

const searchFields = (product: Product) => [product.name, product.sku, product.description];

/**
 * The products catalog, on the Records-browser standard (D16): the shared table/grid view with
 * selection, sorting, density, column visibility, and facet filters, over the workspace catalog the
 * page delivered.
 *
 * Two pieces of the standard do not apply. Products have no detail route and no peek — `PRODUCT.md`
 * §7 keeps them managed in place — so a row opens the edit dialog rather than a record page. And the
 * catalog arrives whole from the server, so filtering, sorting, and paging are client-side: there is
 * no product page endpoint, and inventing one is a backend change this surface does not need.
 */
export default function ProductsBrowser({ products: initial }: { products: Product[] }) {
    const t = useTranslations('ProductsBrowser');
    const layoutT = useTranslations('RecordsProductsLayout');
    const tf = useTranslations('Filters');
    const locale = useLocale();
    const reduce = useReducedMotion() ?? false;
    const showApiError = useApiErrorToast('ProductsBrowser');

    const [products, setProducts] = useState(initial);
    const [dialog, setDialog] = useState<{ mode: 'create' | 'edit'; product?: Product } | null>(null);
    const [isDeleting, setIsDeleting] = useState(false);

    const {
        displayMode,
        effectiveDisplayMode,
        setDisplayMode,
        query,
        setQuery,
        filterState,
        setFilterState,
        selectedIds,
        setSelectedIds,
        filteredItems,
        selectedItems,
        deleteDialogOpen,
        setDeleteDialogOpen,
    } = useRecordsBrowser<Product>({
        items: products,
        storageKey: 'products:view',
        searchFields,
    });

    const { density, setDensity } = useRecordDensity();
    const { sortKey, sortDirection, onSortChange, sortState } = useRecordsSort('name');

    const columns: ColumnDef<Product>[] = useMemo(() => [
        {
            key: 'name',
            label: t('columnName'),
            getSortValue: (product) => product.name,
            widthClass: 'min-w-56',
            render: (product) => (
                <span className="block min-w-0">
                    <span className="block truncate font-medium text-foreground">{product.name}</span>
                    {product.description ? (
                        <span className="block truncate text-xs text-muted-foreground">{product.description}</span>
                    ) : null}
                </span>
            ),
        },
        {
            key: 'sku',
            label: t('columnSku'),
            getSortValue: (product) => product.sku ?? null,
            render: (product) => product.sku || '—',
            copyable: { label: t('columnSku'), getValue: (product) => product.sku },
        },
        {
            key: 'unitPrice',
            label: t('columnPrice'),
            getSortValue: (product) => product.unitPrice,
            render: (product) => (
                <span className="whitespace-nowrap tabular-nums">
                    {formatCurrency(product.unitPrice, product.currency, locale)}
                    {product.unit ? (
                        <span className="ml-1 text-xs text-muted-foreground">
                            {t('perUnit', { unit: product.unit })}
                        </span>
                    ) : null}
                </span>
            ),
        },
        {
            key: 'billingFrequency',
            label: t('columnBilling'),
            getSortValue: (product) => product.billingFrequency,
            render: (product) => (product.billingFrequency === 'recurring' ? t('recurring') : t('oneTime')),
            filter: {
                getValue: (product) => product.billingFrequency,
                formatValue: (value) => (value === 'recurring' ? t('recurring') : t('oneTime')),
            },
        },
        {
            key: 'taxRate',
            label: t('columnTax'),
            getSortValue: (product) => product.taxRate ?? null,
            render: (product) => <span className="tabular-nums">{formatTaxRate(product, locale)}</span>,
        },
        {
            key: 'active',
            label: t('columnAvailability'),
            getSortValue: (product) => (product.active ? 1 : 0),
            render: (product) => (
                <ProductAvailability
                    active={product.active}
                    activeLabel={t('active')}
                    inactiveLabel={t('inactive')}
                />
            ),
            filter: {
                getValue: (product) => String(product.active),
                formatValue: (value) => (value === 'true' ? t('active') : t('inactive')),
            },
        },
        {
            key: 'effectiveStart',
            label: t('effectiveDates'),
            getSortValue: (product) => (product.effectiveStart ? Date.parse(product.effectiveStart) : null),
            render: (product) => formatEffectiveRange(product, locale, t),
            filter: {
                getValue: (product) => (product.effectiveStart || product.effectiveEnd ? 'bounded' : null),
                formatValue: () => t('filterBounded'),
                emptyLabel: t('noDateLimit'),
            },
        },
    ], [locale, t]);

    const { visibleColumns, toggles, setColumnVisible, resetColumns, hiddenCount } =
        useColumnVisibility('product', columns, { lockedKey: sortKey });

    const facets = useMemo(() => deriveFilterOptions(columns, products), [columns, products]);
    const rows = useMemo(
        () => applyRecordFilters(filteredItems, columns, filterState),
        [filteredItems, columns, filterState],
    );

    const trimmedQuery = query.trim();
    const filtersActive = trimmedQuery !== '' || countActiveFilters(filterState) > 0;
    const clearAll = useCallback(() => {
        setQuery('');
        setFilterState({});
    }, [setFilterState, setQuery]);

    const chips: FilterChipData[] = [
        ...(trimmedQuery
            ? [{ id: 'q', label: tf('chipSearch', { query: trimmedQuery }), onRemove: () => setQuery('') }]
            : []),
        ...facetChips(facets, filterState, setFilterState),
    ];

    const upsert = (saved: Product) => {
        setProducts((prev) => prev.some((product) => product.id === saved.id)
            ? prev.map((product) => (product.id === saved.id ? saved : product))
            : [...prev, saved].sort((a, b) => a.name.localeCompare(b.name)));
    };

    /**
     * Exports what `GET /api/exports/products` can express, which is the search and nothing else.
     * The facets on this browser are client-side, and the endpoint takes no filter params, so the
     * action's copy names the search rather than claiming the whole view; widening the endpoint is
     * a backend change this surface does not make.
     */
    const exportProducts = useCallback(
        (signal: AbortSignal, workspaceId: number) => exportProductsCsv(
            { q: trimmedQuery || undefined },
            { signal, headers: { 'X-Workspace-Id': String(workspaceId) } },
        ),
        [trimmedQuery],
    );

    const editOne = useCallback((product: Product) => setDialog({ mode: 'edit', product }), []);
    const deleteOne = useCallback((product: Product) => {
        setSelectedIds(new Set([product.id]));
        setDeleteDialogOpen(true);
    }, [setDeleteDialogOpen, setSelectedIds]);

    const confirmDelete = async () => {
        if (selectedIds.size === 0) return;
        setIsDeleting(true);
        const targets = Array.from(selectedIds, Number);
        try {
            await Promise.all(targets.map((id) => deleteProduct(id)));
            setProducts((prev) => prev.filter((product) => !selectedIds.has(product.id)));
            setSelectedIds(new Set());
            setDeleteDialogOpen(false);
            toastSuccess(targets.length === 1 ? t('deleted') : t('deletedMany', { count: targets.length }));
        } catch (err) {
            showApiError(err, 'deleteFailed');
        } finally {
            setIsDeleting(false);
        }
    };

    const selectionActions = (
        <ButtonGroup className="rounded-full bg-muted">
            {selectedItems.length === 1 ? (
                <Button variant="outline" size="toolbar" onClick={() => editOne(selectedItems[0])}>
                    <PencilIcon className="size-4" />
                    {t('edit')}
                </Button>
            ) : null}
            <Button variant="outline" size="toolbar" onClick={() => setDeleteDialogOpen(true)}>
                <TrashIcon className="size-4" />
                {t('delete')}
            </Button>
        </ButtonGroup>
    );

    return (
        <>
            <PageShell>
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
                    <FilterBar
                        reduce={reduce}
                        chips={chips}
                        hasActiveFilters={filtersActive}
                        onClearAll={clearAll}
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
                        collapsed={
                            <RecordsFilterSheet<Product>
                                columns={columns}
                                sortKey={sortKey}
                                sortDirection={sortDirection}
                                onSortChange={onSortChange}
                                facets={facets}
                                filterState={filterState}
                                onFilterStateChange={setFilterState}
                                hasActiveFilters={filtersActive}
                                onClearAll={clearAll}
                            />
                        }
                        trailing={
                            <div className="flex items-center gap-2">
                                {effectiveDisplayMode !== 'table' && (
                                    <RecordsSortMenu
                                        columns={columns}
                                        sortKey={sortKey}
                                        sortDirection={sortDirection}
                                        onSortChange={onSortChange}
                                    />
                                )}
                                <SegmentedControl
                                    ariaLabel={t('displayModeAria')}
                                    value={displayMode}
                                    onChange={setDisplayMode}
                                    options={[
                                        { value: 'grid', icon: <Squares2X2Icon className="size-4" />, ariaLabel: t('gridViewAria') },
                                        { value: 'table', icon: <TableCellsIcon className="size-4" />, ariaLabel: t('tableViewAria') },
                                    ]}
                                    className="hidden md:inline-flex"
                                />
                                {effectiveDisplayMode === 'table' && <DensityToggle value={density} onChange={setDensity} />}
                                {effectiveDisplayMode === 'table' && (
                                    <ColumnVisibilityMenu
                                        toggles={toggles}
                                        onColumnVisibleChange={setColumnVisible}
                                        onReset={resetColumns}
                                        hiddenCount={hiddenCount}
                                    />
                                )}
                            </div>
                        }
                    >
                        <RecordsFilterPills<Product>
                            facets={facets}
                            filterState={filterState}
                            onChange={setFilterState}
                        />
                    </FilterBar>
                </Rise>

                <Rise delay={0.12}>
                    <RecordsRenderView<Product>
                        data={rows}
                        columns={visibleColumns}
                        renderCard={(item, { onQuickEdit, onDelete }) => (
                            <ProductCard
                                product={item}
                                onQuickEdit={onQuickEdit ? () => onQuickEdit(item) : undefined}
                                onDelete={onDelete ? () => onDelete(item) : undefined}
                            />
                        )}
                        renderListRow={(item) => <ProductListRow product={item} />}
                        onRowClick={editOne}
                        displayMode={effectiveDisplayMode}
                        density={density}
                        selectedIds={selectedIds}
                        onSelectedIdsChange={setSelectedIds}
                        onQuickEdit={editOne}
                        onDelete={deleteOne}
                        sortState={sortState}
                        gridClassName="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3"
                        entityLabel={t('entityLabel')}
                        selectionActions={selectionActions}
                        emptyState={
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
                        }
                        filtersActive={filtersActive}
                        onClearFilters={clearAll}
                    />
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
                open={deleteDialogOpen}
                onOpenChange={setDeleteDialogOpen}
                selectedIds={selectedIds}
                selectedItems={selectedItems}
                entityLabel={t('entityLabel')}
                getDisplayName={(product) => product.name}
                isDeleting={isDeleting}
                confirmDelete={confirmDelete}
            />
        </>
    );
}
