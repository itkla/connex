'use client';

import { useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useLocale, useTranslations } from 'next-intl';
import { PlusIcon, TrashIcon } from '@heroicons/react/24/outline';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
    Combobox,
    ComboboxContent,
    ComboboxEmpty,
    ComboboxInput,
    ComboboxItem,
    ComboboxList,
} from '@/components/ui/combobox';
import SectionHeader from '@/app/components/dashboard/SectionHeader';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { formatCurrency } from '@/app/lib/utils';
import {
    getProducts,
    createDealLineItem,
    updateDealLineItem,
    deleteDealLineItem,
} from '@/app/lib/api';
import type { DealLineItem, DealLineItemPayload, DealLineItemTotals, DealLineItemsResponse, Product } from '@/app/lib/types';

type Props = {
    dealId: number;
    dealCurrency: string;
    initial: DealLineItemsResponse;
};

const EMPTY_TOTALS = (currency: string): DealLineItemTotals => ({
    currency, subtotal: 0, tax: 0, oneTimeTotal: 0, recurringTotal: 0, grandTotal: 0,
});

/**
 * Editable line-items table for a deal. All monetary values are server-computed: every add / edit /
 * remove posts to the server and replaces local state with the returned items + totals. The client
 * never does money arithmetic.
 */
export default function DealLineItems({ dealId, dealCurrency, initial }: Props) {
    const router = useRouter();
    const t = useTranslations('DealsLineItems');
    const showApiError = useApiErrorToast('DealsLineItems');
    const locale = useLocale();
    const [items, setItems] = useState<DealLineItem[]>(initial.items);
    const [totals, setTotals] = useState<DealLineItemTotals>(initial.totals ?? EMPTY_TOTALS(dealCurrency));
    const [products, setProducts] = useState<Product[]>([]);
    const [busy, setBusy] = useState(false);

    useEffect(() => {
        getProducts().then((all) => setProducts(all.filter((p) => p.active))).catch(() => setProducts([]));
    }, []);

    const currency = totals.currency ?? dealCurrency;
    const money = (value: number) => formatCurrency(value, currency, locale);

    const apply = (res: DealLineItemsResponse) => {
        setItems(res.items);
        setTotals(res.totals ?? EMPTY_TOTALS(dealCurrency));
    };

    const run = async (op: () => Promise<DealLineItemsResponse>) => {
        setBusy(true);
        try {
            apply(await op());
            router.refresh();
        } catch (err) {
            showApiError(err, 'saveFailed');
        } finally {
            setBusy(false);
        }
    };

    const addFromProduct = (product: Product) =>
        run(() => createDealLineItem(dealId, { productId: product.id, quantity: 1 }));

    const addAdhoc = () =>
        run(() => createDealLineItem(dealId, { name: t('newLineName'), unitPrice: 0, quantity: 1 }));

    const patchLine = (item: DealLineItem, payload: DealLineItemPayload) =>
        run(() => updateDealLineItem(dealId, item.id, payload));

    const removeLine = (item: DealLineItem) =>
        run(() => deleteDealLineItem(dealId, item.id));

    const productItems = useMemo(
        () => products.filter((p) => (p.currency ?? dealCurrency) === currency),
        [products, currency, dealCurrency],
    );

    return (
        <section>
            <div className="mb-3 flex items-center justify-between">
                <SectionHeader title={t('title')} />
                <div className="flex items-center gap-2">
                    <Combobox
                        items={productItems}
                        itemToStringLabel={(p: Product) => p.name}
                        value={null}
                        onValueChange={(p) => { if (p) addFromProduct(p as Product); }}
                    >
                        <ComboboxInput placeholder={t('addFromCatalog')} className="w-56" disabled={busy} />
                        <ComboboxContent>
                            <ComboboxList>
                                <ComboboxEmpty>{t('noProducts')}</ComboboxEmpty>
                                {productItems.map((p) => (
                                    <ComboboxItem key={p.id} value={p}>
                                        <span className="flex w-full items-center justify-between gap-3">
                                            <span className="truncate">{p.name}</span>
                                            <span className="shrink-0 tabular-nums text-muted-foreground">
                                                {formatCurrency(p.unitPrice, p.currency ?? currency, locale)}
                                            </span>
                                        </span>
                                    </ComboboxItem>
                                ))}
                            </ComboboxList>
                        </ComboboxContent>
                    </Combobox>
                    <Button variant="outline" size="sm" onClick={addAdhoc} disabled={busy}>
                        <PlusIcon className="size-4" />
                        {t('addLine')}
                    </Button>
                </div>
            </div>

            {items.length === 0 ? (
                <div className="rounded-2xl border border-border bg-card px-6 py-12 text-center text-sm text-muted-foreground">
                    {t('empty')}
                </div>
            ) : (
                <div className="overflow-hidden rounded-2xl border border-border bg-card">
                    <table className="w-full text-sm">
                        <thead>
                            <tr className="border-b border-border text-left text-xs uppercase tracking-[0.08em] text-muted-foreground">
                                <th className="px-4 py-3 font-medium">{t('columnItem')}</th>
                                <th className="w-24 px-4 py-3 font-medium text-right">{t('columnQty')}</th>
                                <th className="w-32 px-4 py-3 font-medium text-right">{t('columnUnitPrice')}</th>
                                <th className="w-28 px-4 py-3 font-medium text-right">{t('columnDiscount')}</th>
                                <th className="w-32 px-4 py-3 font-medium text-right">{t('columnLineTotal')}</th>
                                <th className="w-10 px-2 py-3" />
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-border">
                            {items.map((item) => (
                                <tr key={item.id} className="align-top">
                                    <td className="px-4 py-3">
                                        <Input
                                            defaultValue={item.name}
                                            aria-label={t('columnItem')}
                                            className="h-8 border-0 bg-transparent px-0 shadow-none focus-visible:bg-muted"
                                            onBlur={(e) => { if (e.target.value !== item.name) patchLine(item, linePayload(item, { name: e.target.value })); }}
                                            disabled={busy}
                                        />
                                        <div className="mt-0.5 text-xs text-muted-foreground">
                                            {item.billingFrequency === 'recurring' ? t('recurring') : t('oneTime')}
                                        </div>
                                    </td>
                                    <td className="px-4 py-3 text-right">
                                        <Input type="number" min="0" step="0.001" inputMode="decimal"
                                            defaultValue={item.quantity}
                                            aria-label={t('columnQty')}
                                            className="h-8 w-20 text-right tabular-nums"
                                            onBlur={(e) => { const v = Number(e.target.value); if (v !== item.quantity) patchLine(item, linePayload(item, { quantity: v })); }}
                                            disabled={busy} />
                                    </td>
                                    <td className="px-4 py-3 text-right">
                                        <Input type="number" min="0" step="0.01" inputMode="decimal"
                                            defaultValue={item.unitPrice}
                                            aria-label={t('columnUnitPrice')}
                                            className="h-8 w-28 text-right tabular-nums"
                                            onBlur={(e) => { const v = Number(e.target.value); if (v !== item.unitPrice) patchLine(item, linePayload(item, { unitPrice: v })); }}
                                            disabled={busy} />
                                    </td>
                                    <td className="px-4 py-3 text-right text-muted-foreground tabular-nums">
                                        {item.discountValue
                                            ? item.discountType === 'percent'
                                                ? `${item.discountValue}%`
                                                : money(item.discountValue)
                                            : '—'}
                                    </td>
                                    <td className="px-4 py-3 text-right font-medium tabular-nums">{money(item.lineTotal)}</td>
                                    <td className="px-2 py-3 text-right">
                                        <Button variant="ghost" size="icon-xs" aria-label={t('remove')}
                                            onClick={() => removeLine(item)} disabled={busy}>
                                            <TrashIcon className="size-4 text-muted-foreground" />
                                        </Button>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                        <tfoot className="border-t border-border bg-muted/40">
                            <TotalRow label={t('subtotal')} value={money(totals.subtotal)} />
                            <TotalRow label={t('tax')} value={money(totals.tax)} />
                            {totals.recurringTotal > 0 && (
                                <>
                                    <TotalRow label={t('oneTimeTotal')} value={money(totals.oneTimeTotal)} muted />
                                    <TotalRow label={t('recurringTotal')} value={money(totals.recurringTotal)} muted />
                                </>
                            )}
                            <TotalRow label={t('grandTotal')} value={money(totals.grandTotal)} emphasis />
                        </tfoot>
                    </table>
                </div>
            )}
        </section>
    );
}

/**
 * Builds a full line payload from the existing item plus a patch, so a single-field edit preserves
 * the other server-relevant fields (the server treats each request as the item's full desired state).
 */
function linePayload(item: DealLineItem, patch: Partial<DealLineItemPayload>): DealLineItemPayload {
    return {
        productId: item.productId ?? undefined,
        name: item.name,
        sku: item.sku ?? undefined,
        unit: item.unit ?? undefined,
        unitPrice: item.unitPrice,
        quantity: item.quantity,
        discountType: item.discountType ?? undefined,
        discountValue: item.discountValue ?? undefined,
        taxRate: item.taxRate ?? undefined,
        billingFrequency: item.billingFrequency,
        description: item.description ?? undefined,
        position: item.position,
        ...patch,
    };
}

function TotalRow({ label, value, emphasis, muted }: { label: string; value: string; emphasis?: boolean; muted?: boolean }) {
    return (
        <tr className={emphasis ? 'text-foreground' : muted ? 'text-muted-foreground' : ''}>
            <td className="px-4 py-2" colSpan={4} />
            <td className={`px-4 py-2 text-right ${emphasis ? 'text-base font-semibold' : 'text-sm'}`}>
                <span className="mr-3 font-normal text-muted-foreground">{label}</span>
                <span className="tabular-nums">{value}</span>
            </td>
            <td className="px-2 py-2" />
        </tr>
    );
}
