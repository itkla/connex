'use client';

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';
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
import { useWorkspace } from '@/app/hooks/useWorkspace';
import { useAccountStorageGeneration } from '@/app/hooks/useAccountStorageGeneration';
import { renderedWorkspaceId, renderedWorkspaceNavigationVersion, workspacePinnedRequest } from '@/app/lib/renderedWorkspace';
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
    dealWorkspaceId: number | null;
    dealCurrency: string;
    initial: DealLineItemsResponse;
};

/**
 * Keeps new lines local until explicitly saved. Proposed totals and persisted amounts are computed
 * by the server; a nonzero manual-value takeover requires separate consent to that exact amount.
 */
export default function DealLineItems({ dealId, dealWorkspaceId, dealCurrency, initial }: Props) {
    const router = useRouter();
    const pathname = usePathname();
    const { activeWorkspaceId } = useWorkspace();
    const [owner] = useState(() => ({ workspaceId: dealWorkspaceId,
        navigation: renderedWorkspaceNavigationVersion(), pathname, dealId }));
    const valid = useRef(true);
    const revoked = useRef(false);
    const requests = useRef(new Set<AbortController>());
    const draftWorkspace = useRef<number | null>(null);
    const [invalidated, setInvalidated] = useState(false);
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
        workspaceId: number | null;
    } | null>(null);

    const isAccountCurrent = useAccountStorageGeneration(() => {
        revoked.current = true;
        valid.current = false;
        for (const controller of requests.current) controller.abort();
        requests.current.clear();
        setPending(null);
        setInvalidated(true);
    });

    useLayoutEffect(() => {
        const abort = () => {
            valid.current = false;
            for (const controller of requests.current) controller.abort();
            requests.current.clear();
        };
        const invalidate = () => {
            revoked.current = true;
            abort();
            setInvalidated(true);
            setPending(null);
        };
        if (owner.workspaceId !== activeWorkspaceId || owner.workspaceId !== dealWorkspaceId
                || owner.pathname !== pathname || owner.dealId !== dealId
                || owner.navigation !== renderedWorkspaceNavigationVersion()) invalidate();
        else valid.current = !revoked.current;
        return abort;
    }, [activeWorkspaceId, pathname, dealId, dealWorkspaceId, owner]);

    const isCurrent = useCallback(() => valid.current && isAccountCurrent() && owner.workspaceId !== null
        && owner.workspaceId === renderedWorkspaceId()
        && owner.navigation === renderedWorkspaceNavigationVersion()
        && owner.pathname === window.location.pathname, [isAccountCurrent, owner]);

    useEffect(() => {
        if (!isCurrent()) return;
        const controller = new AbortController();
        const origin = workspacePinnedRequest(owner.workspaceId, { signal: controller.signal });
        const activeRequests = requests.current;
        activeRequests.add(controller);
        getProducts({}, origin)
            .then((all) => { if (!controller.signal.aborted && isCurrent()) setProducts(all.filter((p) => p.active)); })
            .catch(() => { if (!controller.signal.aborted && isCurrent()) setProducts([]); })
            .finally(() => activeRequests.delete(controller));
        return () => { controller.abort(); activeRequests.delete(controller); };
    }, [isCurrent, owner]);

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

    const writeLine = (payload: DealLineItemPayload, origin: RequestInit, itemId?: number) => itemId === undefined
        ? createDealLineItem(dealId, payload, origin)
        : updateDealLineItem(dealId, itemId, payload, origin);

    const saveLine = async (payload: DealLineItemPayload, itemId?: number) => {
        if (!isCurrent()) return;
        const workspaceId = itemId === undefined ? draftWorkspace.current : owner.workspaceId;
        const controller = new AbortController();
        const origin = workspacePinnedRequest(workspaceId, { signal: controller.signal });
        requests.current.add(controller);
        setBusy(true);
        try {
            const preview = await writeLine({ ...payload, previewOnly: true }, origin, itemId);
            if (!isCurrent() || controller.signal.aborted) return;
            if (preview.manualValueToReplace != null) {
                if (preview.totals === null) throw new Error('Line totals unavailable');
                setPending({ payload, itemId, manual: preview.manualValueToReplace, total: preview.totals.grandTotal,
                    currency: preview.totals.currency ?? dealCurrency, workspaceId });
            } else {
                const saved = await writeLine(payload, origin, itemId);
                if (!isCurrent() || controller.signal.aborted) return;
                apply(saved);
                if (itemId === undefined) setDraft(null);
                router.refresh();
            }
        } catch (err) {
            if (isCurrent()) showApiError(err, 'saveFailed');
        } finally {
            requests.current.delete(controller);
            if (isCurrent()) setBusy(false);
        }
    };

    const confirmLine = async () => {
        if (!pending || !isCurrent()) return;
        const workspaceId = pending.workspaceId;
        const controller = new AbortController();
        const origin = workspacePinnedRequest(workspaceId, { signal: controller.signal });
        requests.current.add(controller);
        setBusy(true);
        try {
            const saved = await writeLine({ ...pending.payload, confirmValueReplacement: true,
                expectedManualValue: pending.manual, expectedCurrency: pending.currency }, origin, pending.itemId);
            if (!isCurrent() || controller.signal.aborted) return;
            apply(saved);
            if (pending.itemId === undefined) setDraft(null);
            setPending(null);
            router.refresh();
        } catch (err) {
            if (isCurrent()) {
                setPending(null);
                showApiError(err, 'saveFailed');
            }
        } finally {
            requests.current.delete(controller);
            if (isCurrent()) setBusy(false);
        }
    };

    const addFromProduct = (product: Product) => {
        if (!isCurrent()) return;
        draftWorkspace.current = renderedWorkspaceId();
        setDraft({ productId: product.id, name: product.name,
        unitPrice: product.unitPrice, quantity: 1, taxRate: product.taxRate ?? 0,
        billingFrequency: product.billingFrequency, sku: product.sku, unit: product.unit });
    };

    const addAdhoc = () => {
        if (!isCurrent()) return;
        draftWorkspace.current = renderedWorkspaceId();
        setDraft({ name: t('newLineName'), quantity: 1 });
    };

    const patchLine = (item: DealLineItem, payload: DealLineItemPayload) =>
        saveLine(payload, item.id);

    const removeLine = async (item: DealLineItem) => {
        if (!isCurrent()) return;
        const controller = new AbortController();
        const origin = workspacePinnedRequest(owner.workspaceId, { signal: controller.signal });
        requests.current.add(controller);
        setBusy(true);
        try {
            const saved = await deleteDealLineItem(dealId, item.id, origin);
            if (!isCurrent() || controller.signal.aborted) return;
            apply(saved);
            router.refresh();
        } catch (error) {
            if (isCurrent()) showApiError(error, 'saveFailed');
        } finally {
            requests.current.delete(controller);
            if (isCurrent()) setBusy(false);
        }
    };

    const productItems = useMemo(
        () => products.filter((p) => (p.currency ?? dealCurrency) === currency),
        [products, currency, dealCurrency],
    );

    if (invalidated) return null;

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
                        <Input type="number" min="0" max={draft.discountType === 'percent' ? 100 : undefined} step="0.01" inputMode="decimal" value={draft.discountValue ?? ''}
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
                <div className="rounded-2xl border border-border bg-card">
                    <div className="divide-y divide-border">
                        {items.map((item) => (
                            <article key={item.id} aria-label={item.name} className="grid grid-cols-2 gap-3 p-4 sm:grid-cols-3">
                                <div className="col-span-2 min-w-0 sm:col-span-3">
                                    <label className="text-xs text-muted-foreground">{t('columnItem')}
                                        <Input defaultValue={item.name} aria-label={t('columnItem')} disabled={busy}
                                            onBlur={(event) => { if (event.target.value !== item.name) patchLine(item, linePayload(item, { name: event.target.value })); }} />
                                    </label>
                                    <p className="mt-1 text-xs text-muted-foreground">
                                        {item.billingFrequency === 'recurring' ? t('recurring') : t('oneTime')}
                                    </p>
                                    {item.currency.toUpperCase() !== dealCurrency.toUpperCase() && (
                                        <p className="mt-1 text-xs text-muted-foreground">
                                            {t('currencyMismatch', { lineCurrency: item.currency, dealCurrency })}
                                        </p>
                                    )}
                                </div>
                                <label className="min-w-0 text-xs text-muted-foreground">{t('columnQty')}
                                    <Input type="number" min="0" step="0.001" inputMode="decimal"
                                        defaultValue={item.quantity} aria-label={t('columnQty')} disabled={busy}
                                        className="w-full tabular-nums"
                                        onBlur={(event) => { if (!event.currentTarget.reportValidity()) return;
                                            const value = Number(event.target.value);
                                            if (value !== item.quantity) patchLine(item, linePayload(item, { quantity: value })); }} />
                                </label>
                                <label className="min-w-0 text-xs text-muted-foreground">{t('columnUnitPrice')}
                                    <Input type="number" min="0" step="0.01" inputMode="decimal"
                                        defaultValue={item.unitPrice} aria-label={t('columnUnitPrice')} disabled={busy}
                                        className="w-full tabular-nums"
                                        onBlur={(event) => { if (!event.currentTarget.reportValidity()) return;
                                            const value = Number(event.target.value);
                                            if (value !== item.unitPrice) patchLine(item, linePayload(item, { unitPrice: value })); }} />
                                    <span>{item.currency}</span>
                                </label>
                                <div className="min-w-0 space-y-1">
                                    <DiscountType value={item.discountType} disabled={busy}
                                        onChange={(discountType) => patchLine(item, linePayload(item, {
                                            discountType, discountValue: discountType ? item.discountValue ?? 0 : null,
                                        }))} />
                                    <label className="block text-xs text-muted-foreground">{t('discountValue')}
                                        <Input type="number" min="0" step="0.01" inputMode="decimal"
                                            max={item.discountType === 'percent' ? 100 : undefined}
                                            key={`${item.id}-${item.discountType}-${item.discountValue}`}
                                            defaultValue={item.discountValue ?? 0} aria-label={t('discountValue')}
                                            className="w-full tabular-nums" disabled={busy || !item.discountType}
                                            onBlur={(event) => { if (!event.currentTarget.reportValidity()) return;
                                                const value = Number(event.target.value);
                                                if (value !== item.discountValue) patchLine(item, linePayload(item, { discountValue: value })); }} />
                                    </label>
                                </div>
                                <label className="min-w-0 text-xs text-muted-foreground">{t('taxRate')}
                                    <Input type="number" min="0" max="999.999" step="0.001" inputMode="decimal"
                                        defaultValue={item.taxRate ?? 0} aria-label={t('taxRate')}
                                        className="w-full tabular-nums" disabled={busy}
                                        onBlur={(event) => { if (!event.currentTarget.reportValidity()) return;
                                            const value = Number(event.target.value);
                                            if (value !== (item.taxRate ?? 0)) patchLine(item, linePayload(item, { taxRate: value })); }} />
                                </label>
                                <div className="col-span-2 flex flex-wrap items-end justify-between gap-3 sm:col-span-3">
                                    <p className="text-sm text-muted-foreground">{t('columnLineTotal')}
                                        <span className="ml-2 font-semibold tabular-nums text-foreground">{money(item.lineTotal, item.currency)}</span>
                                    </p>
                                    <Button variant="ghost" size="sm" onClick={() => removeLine(item)} disabled={busy}>
                                        <TrashIcon className="size-4" />{t('remove')}
                                    </Button>
                                </div>
                            </article>
                        ))}
                    </div>
                    {totals !== null && <dl className="space-y-2 rounded-b-2xl border-t border-border bg-muted/40 p-4">
                        <TotalRow label={t('subtotal')} value={money(totals.subtotal)} />
                        <TotalRow label={t('tax')} value={money(totals.tax)} />
                        {totals.recurringTotal > 0 && <>
                            <TotalRow label={t('oneTimeTotal')} value={money(totals.oneTimeTotal)} />
                            <TotalRow label={t('recurringTotal')} value={money(totals.recurringTotal)} />
                        </>}
                        <TotalRow label={t('grandTotal')} value={money(totals.grandTotal)} emphasis />
                    </dl>}
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

function TotalRow({ label, value, emphasis }: { label: string; value: string; emphasis?: boolean }) {
    return <div className={`flex items-center justify-between gap-4 ${emphasis ? 'font-semibold' : 'text-sm'}`}>
        <dt className="text-muted-foreground">{label}</dt>
        <dd className="tabular-nums">{value}</dd>
    </div>;
}
