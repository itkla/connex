'use client';

import { useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useLocale, useTranslations } from 'next-intl';
import { PlusIcon, TrashIcon } from '@heroicons/react/24/outline';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
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

/**
 * Keeps new lines local until explicitly saved. Proposed totals and persisted amounts are computed
 * by the server; a nonzero manual-value takeover requires separate consent to that exact amount.
 */
export default function DealLineItems({ dealId, dealCurrency, initial }: Props) {
    const router = useRouter();
    const t = useTranslations('DealsLineItems');
    const showApiError = useApiErrorToast('DealsLineItems');
    const locale = useLocale();
    const [items, setItems] = useState<DealLineItem[]>(initial.items);
    const [totals, setTotals] = useState<DealLineItemTotals | null>(initial.totals);
    const [products, setProducts] = useState<Product[]>([]);
    const [busy, setBusy] = useState(false);
    const [draft, setDraft] = useState<DealLineItemPayload | null>(null);
    const [restorable, setRestorable] = useState(initial.restorableManualValue);
    const [pending, setPending] = useState<{
        payload: DealLineItemPayload;
        itemId?: number;
        manual: number;
        total: number;
        currency: string;
    } | null>(null);

    useEffect(() => {
        getProducts().then((all) => setProducts(all.filter((p) => p.active))).catch(() => setProducts([]));
    }, []);

    const currency = totals?.currency ?? dealCurrency;
    const money = (value: number, lineCurrency = currency) => formatCurrency(value, lineCurrency, locale);
    const exactMoney = (value: number, lineCurrency = dealCurrency) => {
        const decimals = { minimumFractionDigits: 2, maximumFractionDigits: 2 };
        try {
            return new Intl.NumberFormat(locale, { ...decimals, style: 'currency', currency: lineCurrency }).format(value);
        } catch {
            return `${lineCurrency} ${new Intl.NumberFormat(locale, decimals).format(value)}`;
        }
    };

    const apply = (res: DealLineItemsResponse) => {
        setItems(res.items);
        setTotals(res.totals);
        setRestorable(res.restorableManualValue);
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

    const writeLine = (payload: DealLineItemPayload, itemId?: number) => itemId === undefined
        ? createDealLineItem(dealId, payload)
        : updateDealLineItem(dealId, itemId, payload);

    const saveLine = async (payload: DealLineItemPayload, itemId?: number) => {
        setBusy(true);
        try {
            const preview = await writeLine({ ...payload, previewOnly: true }, itemId);
            if (preview.manualValueToReplace != null) {
                if (preview.totals === null) throw new Error('Line totals unavailable');
                setPending({ payload, itemId, manual: preview.manualValueToReplace, total: preview.totals.grandTotal,
                    currency: preview.totals.currency ?? dealCurrency });
            } else {
                apply(await writeLine(payload, itemId));
                if (itemId === undefined) setDraft(null);
                router.refresh();
            }
        } catch (err) {
            showApiError(err, 'saveFailed');
        } finally {
            setBusy(false);
        }
    };

    const confirmLine = async () => {
        if (!pending) return;
        setBusy(true);
        try {
            apply(await writeLine({ ...pending.payload, confirmValueReplacement: true,
                expectedManualValue: pending.manual, expectedCurrency: pending.currency }, pending.itemId));
            if (pending.itemId === undefined) setDraft(null);
            setPending(null);
            router.refresh();
        } catch (err) {
            setPending(null);
            showApiError(err, 'saveFailed');
        } finally {
            setBusy(false);
        }
    };

    const addFromProduct = (product: Product) => setDraft({ productId: product.id, name: product.name,
        unitPrice: product.unitPrice, quantity: 1, taxRate: product.taxRate ?? 0,
        billingFrequency: product.billingFrequency, sku: product.sku, unit: product.unit });

    const addAdhoc = () => setDraft({ name: t('newLineName'), quantity: 1 });

    const patchLine = (item: DealLineItem, payload: DealLineItemPayload) =>
        saveLine(payload, item.id);

    const removeLine = (item: DealLineItem) =>
        run(() => deleteDealLineItem(dealId, item.id));

    const productItems = useMemo(
        () => products.filter((p) => (p.currency ?? dealCurrency) === currency),
        [products, currency, dealCurrency],
    );

    return (
        <section aria-label={t('title')} className="min-w-0">
            <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
                <SectionHeader title={t('title')} />
                <div className="flex w-full flex-wrap items-center gap-2 sm:w-auto">
                    <Combobox
                        items={productItems}
                        itemToStringLabel={(p: Product) => p.name}
                        value={null}
                        onValueChange={(p) => { if (p) addFromProduct(p); }}
                    >
                        <ComboboxInput placeholder={t('addFromCatalog')} className="w-full sm:w-56" disabled={busy || draft !== null} />
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
                    <Button variant="outline" size="sm" className="shrink-0" onClick={addAdhoc} disabled={busy || draft !== null}>
                        <PlusIcon className="size-4" />
                        {t('addLine')}
                    </Button>
                </div>
            </div>

            {draft && <form aria-label={t('draftTitle')} className="mb-4 rounded-2xl border border-border bg-card p-4"
                onSubmit={(event) => { event.preventDefault(); void saveLine(draft); }}>
                <p className="mb-3 text-sm text-muted-foreground">{t('draftHelp')}</p>
                <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
                    <label className="col-span-2 text-sm sm:col-span-3">{t('columnItem')}
                        <Input required maxLength={255} value={draft.name ?? ''} disabled={busy}
                            onChange={(event) => setDraft({ ...draft, name: event.target.value })} />
                    </label>
                    <label className="text-sm">{t('columnQty')}
                        <Input required type="number" min="0" step="0.001" inputMode="decimal" value={draft.quantity}
                            disabled={busy} onChange={(event) => setDraft({ ...draft, quantity: Number(event.target.value) })} />
                    </label>
                    <label className="text-sm">{t('columnUnitPrice')}
                        <Input required type="number" min="0" step="0.01" inputMode="decimal" value={draft.unitPrice ?? ''}
                            disabled={busy} onChange={(event) => setDraft({ ...draft,
                                unitPrice: event.target.value === '' ? undefined : Number(event.target.value) })} />
                    </label>
                    <label className="text-sm">{t('taxRate')}
                        <Input type="number" min="0" max="999.999" step="0.001" inputMode="decimal" value={draft.taxRate ?? ''}
                            disabled={busy} onChange={(event) => setDraft({ ...draft, taxRate: Number(event.target.value) })} />
                    </label>
                    <DiscountType value={draft.discountType} disabled={busy}
                        onChange={(discountType) => setDraft({ ...draft, discountType, discountValue: discountType ? draft.discountValue ?? 0 : null })} />
                    <label className="text-sm">{t('discountValue')}
                        <Input type="number" min="0" step="0.01" inputMode="decimal" value={draft.discountValue ?? ''}
                            disabled={busy || !draft.discountType}
                            onChange={(event) => setDraft({ ...draft, discountValue: Number(event.target.value) })} />
                    </label>
                </div>
                <div className="mt-4 flex flex-wrap justify-end gap-2">
                    <Button type="button" variant="outline" disabled={busy} onClick={() => setDraft(null)}>{t('discardDraft')}</Button>
                    <Button type="submit" disabled={busy || draft.unitPrice === undefined}>{t('saveLine')}</Button>
                </div>
            </form>}

            <Dialog open={pending !== null} onOpenChange={(open) => { if (!open && !busy) setPending(null); }}>
                <DialogContent showCloseButton={!busy}>
                    <DialogHeader>
                        <DialogTitle>{t('confirmTitle')}</DialogTitle>
                        <DialogDescription>{pending && t('confirmDescription', {
                            previous: exactMoney(pending.manual, pending.currency), next: exactMoney(pending.total, pending.currency),
                        })}</DialogDescription>
                    </DialogHeader>
                    <DialogFooter>
                        <Button variant="outline" disabled={busy} onClick={() => setPending(null)}>{t('cancel')}</Button>
                        <Button disabled={busy} onClick={() => { void confirmLine(); }}>{t('confirmReplacement')}</Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>

            {items.length === 1 && restorable != null && <p className="mb-3 text-sm text-muted-foreground">
                {t('restoreHelp', { amount: exactMoney(restorable) })}
            </p>}

            {totals === null && (
                <p role="status" className="mb-3 text-sm text-muted-foreground">
                    {t('totalsUnavailable')}
                </p>
            )}

            {items.length === 0 ? (
                <div className="rounded-2xl border border-border bg-card px-6 py-12 text-center text-sm text-muted-foreground">
                    {t('empty')}
                </div>
            ) : (
                <div className="overflow-x-auto rounded-2xl border border-border bg-card">
                    <table className="w-full text-sm">
                        <thead>
                            <tr className="border-b border-border text-left text-xs uppercase tracking-[0.08em] text-muted-foreground">
                                <th className="px-4 py-3 font-medium">{t('columnItem')}</th>
                                <th className="w-24 px-4 py-3 font-medium text-right">{t('columnQty')}</th>
                                <th className="w-32 px-4 py-3 font-medium text-right">{t('columnUnitPrice')}</th>
                                <th className="w-28 px-4 py-3 font-medium text-right">{t('columnDiscount')}</th>
                                <th className="w-28 px-4 py-3 font-medium text-right">{t('taxRate')}</th>
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
                                        {item.currency.toUpperCase() !== dealCurrency.toUpperCase() && (
                                            <p className="mt-1 text-xs text-muted-foreground">
                                                {t('currencyMismatch', { lineCurrency: item.currency, dealCurrency })}
                                            </p>
                                        )}
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
                                        <span className="text-xs text-muted-foreground">{item.currency}</span>
                                    </td>
                                    <td className="px-4 py-3 text-right tabular-nums">
                                        <DiscountType value={item.discountType} disabled={busy}
                                            onChange={(discountType) => patchLine(item, linePayload(item, {
                                                discountType, discountValue: discountType ? item.discountValue ?? 0 : null,
                                            }))} />
                                        <Input type="number" min="0" step="0.01" inputMode="decimal"
                                            key={`${item.id}-${item.discountType}-${item.discountValue}`}
                                            defaultValue={item.discountValue ?? 0} aria-label={t('discountValue')}
                                            className="mt-1 h-8 w-28 text-right" disabled={busy || !item.discountType}
                                            onBlur={(event) => { const value = Number(event.target.value);
                                                if (value !== item.discountValue) patchLine(item, linePayload(item, { discountValue: value })); }} />
                                    </td>
                                    <td className="px-4 py-3 text-right">
                                        <Input type="number" min="0" max="999.999" step="0.001" inputMode="decimal"
                                            defaultValue={item.taxRate ?? 0} aria-label={t('taxRate')}
                                            className="h-8 w-24 text-right" disabled={busy}
                                            onBlur={(event) => { const value = Number(event.target.value);
                                                if (value !== (item.taxRate ?? 0)) patchLine(item, linePayload(item, { taxRate: value })); }} />
                                    </td>
                                    <td className="px-4 py-3 text-right font-medium tabular-nums">{money(item.lineTotal, item.currency)}</td>
                                    <td className="sticky right-0 bg-card px-2 py-3 text-right">
                                        <Button variant="ghost" size="icon" aria-label={t('remove')}
                                            onClick={() => removeLine(item)} disabled={busy}>
                                            <TrashIcon className="size-4 text-muted-foreground" />
                                        </Button>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                        {totals !== null && <tfoot className="border-t border-border bg-muted/40">
                            <TotalRow label={t('subtotal')} value={money(totals.subtotal)} />
                            <TotalRow label={t('tax')} value={money(totals.tax)} />
                            {totals.recurringTotal > 0 && (
                                <>
                                    <TotalRow label={t('oneTimeTotal')} value={money(totals.oneTimeTotal)} muted />
                                    <TotalRow label={t('recurringTotal')} value={money(totals.recurringTotal)} muted />
                                </>
                            )}
                            <TotalRow label={t('grandTotal')} value={money(totals.grandTotal)} emphasis />
                        </tfoot>}
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
        servicePeriodStart: item.servicePeriodStart,
        servicePeriodEnd: item.servicePeriodEnd,
        position: item.position,
        ...patch,
    };
}

function DiscountType({ value, disabled, onChange }: {
    value: DealLineItemPayload['discountType'];
    disabled: boolean;
    onChange: (value: DealLineItemPayload['discountType']) => void;
}) {
    const t = useTranslations('DealsLineItems');
    return <div className="text-sm">
        <Select value={value ?? 'none'} disabled={disabled} onValueChange={(next) => {
            if (next === 'none' || next === 'amount' || next === 'percent') onChange(next === 'none' ? null : next);
        }}>
            <SelectTrigger aria-label={t('discountType')} className="w-full"><SelectValue /></SelectTrigger>
            <SelectContent>
                <SelectItem value="none">{t('discountNone')}</SelectItem>
                <SelectItem value="percent">{t('discountPercent')}</SelectItem>
                <SelectItem value="amount">{t('discountAmount')}</SelectItem>
            </SelectContent>
        </Select>
    </div>;
}

function TotalRow({ label, value, emphasis, muted }: { label: string; value: string; emphasis?: boolean; muted?: boolean }) {
    return (
        <tr className={emphasis ? 'text-foreground' : muted ? 'text-muted-foreground' : ''}>
            <td className="px-4 py-2" colSpan={5} />
            <td className={`px-4 py-2 text-right ${emphasis ? 'text-base font-semibold' : 'text-sm'}`}>
                <span className="mr-3 font-normal text-muted-foreground">{label}</span>
                <span className="tabular-nums">{value}</span>
            </td>
            <td className="px-2 py-2" />
        </tr>
    );
}
