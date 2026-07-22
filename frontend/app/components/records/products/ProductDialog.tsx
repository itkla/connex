'use client';

import { useState } from 'react';
import { useTranslations } from 'next-intl';
import { Loader2Icon } from 'lucide-react';

import {
    Dialog,
    DialogClose,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from '@/components/ui/dialog';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from '@/components/ui/select';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { DialogStatusCover, resolveDialogStatus } from '@/components/ui/dialog-status-cover';
import { toastError } from '@/app/lib/toast';
import { createProduct, updateProduct, ApiError } from '@/app/lib/api';
import { CURRENCY_CODES } from '@/app/lib/currencies';
import type { BillingFrequency, CreateProductPayload, Product } from '@/app/lib/types';

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    mode: 'create' | 'edit';
    product?: Product | null;
    onSaved: (product: Product) => void;
};

type Draft = {
    sku: string;
    name: string;
    description: string;
    unit: string;
    unitPrice: string;
    currency: string;
    taxRate: string;
    billingFrequency: BillingFrequency;
    active: boolean;
};

function toDraft(product?: Product | null): Draft {
    return {
        sku: product?.sku ?? '',
        name: product?.name ?? '',
        description: product?.description ?? '',
        unit: product?.unit ?? '',
        unitPrice: product?.unitPrice != null ? String(product.unitPrice) : '',
        currency: product?.currency ?? 'USD',
        taxRate: product?.taxRate != null ? String(product.taxRate) : '',
        billingFrequency: product?.billingFrequency ?? 'one_time',
        active: product?.active ?? true,
    };
}

/**
 * Create/edit form for a catalog product. Money fields are plain numeric inputs sent to the
 * server, which owns all monetary arithmetic — this dialog never computes totals.
 */
export default function ProductDialog({ open, onOpenChange, mode, product, onSaved }: Props) {
    const t = useTranslations('ProductDialog');
    const [draft, setDraft] = useState<Draft>(() => toDraft(product));
    const [saving, setSaving] = useState(false);
    const [succeeded, setSucceeded] = useState(false);

    const patch = (next: Partial<Draft>) => setDraft((prev) => ({ ...prev, ...next }));

    const submit = async () => {
        const name = draft.name.trim();
        if (!name) {
            toastError(t('nameRequired'));
            return;
        }
        setSaving(true);
        try {
            const payload: CreateProductPayload = {
                sku: draft.sku.trim() || null,
                name,
                description: draft.description.trim() || null,
                active: draft.active,
                unit: draft.unit.trim() || null,
                unitPrice: draft.unitPrice.trim() === '' ? 0 : Number(draft.unitPrice),
                currency: draft.currency,
                taxRate: draft.taxRate.trim() === '' ? null : Number(draft.taxRate),
                billingFrequency: draft.billingFrequency,
            };
            const saved = mode === 'create'
                ? await createProduct(payload)
                : await updateProduct(product!.id, payload);
            setSucceeded(true);
            setTimeout(() => {
                setSucceeded(false);
                onSaved(saved);
                onOpenChange(false);
            }, 700);
        } catch (err) {
            toastError(err instanceof ApiError ? err.message : t('saveFailed'));
        } finally {
            setSaving(false);
        }
    };

    const status = resolveDialogStatus({ isLoading: saving, isSuccess: succeeded });

    return (
        <Dialog open={open} onOpenChange={(next) => { if (!saving) onOpenChange(next); }}>
            <DialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                <DialogStatusCover status={status} />
                <div className="px-6 pb-6">
                    <DialogHeader className="-mt-12 mb-5">
                        <DialogTitle className="text-xl font-semibold tracking-tight">
                            {mode === 'create' ? t('createTitle') : t('editTitle')}
                        </DialogTitle>
                        <DialogDescription>{t('description')}</DialogDescription>
                    </DialogHeader>

                    <form
                        onSubmit={(e) => { e.preventDefault(); if (!saving) submit(); }}
                        className="grid gap-4"
                    >
                        <div className="grid gap-1.5">
                            <Label htmlFor="product-name">{t('name')}</Label>
                            <Input id="product-name" value={draft.name} maxLength={255}
                                onChange={(e) => patch({ name: e.target.value })} autoFocus />
                        </div>

                        <div className="grid grid-cols-2 gap-4">
                            <div className="grid gap-1.5">
                                <Label htmlFor="product-sku">{t('sku')}</Label>
                                <Input id="product-sku" value={draft.sku} maxLength={64}
                                    onChange={(e) => patch({ sku: e.target.value })} />
                            </div>
                            <div className="grid gap-1.5">
                                <Label htmlFor="product-unit">{t('unit')}</Label>
                                <Input id="product-unit" value={draft.unit} maxLength={32}
                                    placeholder={t('unitPlaceholder')}
                                    onChange={(e) => patch({ unit: e.target.value })} />
                            </div>
                        </div>

                        <div className="grid grid-cols-3 gap-4">
                            <div className="grid gap-1.5">
                                <Label htmlFor="product-price">{t('unitPrice')}</Label>
                                <Input id="product-price" type="number" min="0" step="0.01" inputMode="decimal"
                                    value={draft.unitPrice}
                                    onChange={(e) => patch({ unitPrice: e.target.value })} />
                            </div>
                            <div className="grid gap-1.5">
                                <Label htmlFor="product-currency">{t('currency')}</Label>
                                <Select value={draft.currency} onValueChange={(v) => patch({ currency: v })}>
                                    <SelectTrigger id="product-currency"><SelectValue /></SelectTrigger>
                                    <SelectContent>
                                        {CURRENCY_CODES.map((c) => (
                                            <SelectItem key={c} value={c}>{c}</SelectItem>
                                        ))}
                                    </SelectContent>
                                </Select>
                            </div>
                            <div className="grid gap-1.5">
                                <Label htmlFor="product-tax">{t('taxRate')}</Label>
                                <Input id="product-tax" type="number" min="0" step="0.001" inputMode="decimal"
                                    value={draft.taxRate}
                                    onChange={(e) => patch({ taxRate: e.target.value })} />
                            </div>
                        </div>

                        <div className="grid gap-1.5">
                            <Label htmlFor="product-billing">{t('billingFrequency')}</Label>
                            <Select value={draft.billingFrequency}
                                onValueChange={(v) => patch({ billingFrequency: v as BillingFrequency })}>
                                <SelectTrigger id="product-billing"><SelectValue /></SelectTrigger>
                                <SelectContent>
                                    <SelectItem value="one_time">{t('oneTime')}</SelectItem>
                                    <SelectItem value="recurring">{t('recurring')}</SelectItem>
                                </SelectContent>
                            </Select>
                        </div>

                        <div className="grid gap-1.5">
                            <Label htmlFor="product-description">{t('descriptionLabel')}</Label>
                            <Input id="product-description" value={draft.description} maxLength={1024}
                                onChange={(e) => patch({ description: e.target.value })} />
                        </div>

                        <label className="flex items-center gap-2 text-sm text-muted-foreground">
                            <input type="checkbox" checked={draft.active}
                                onChange={(e) => patch({ active: e.target.checked })} />
                            {t('active')}
                        </label>

                        <DialogFooter className="mt-2">
                            <DialogClose asChild>
                                <Button type="button" variant="outline" disabled={saving}>{t('cancel')}</Button>
                            </DialogClose>
                            <Button type="submit" variant="brand" disabled={saving || succeeded} className="min-w-24">
                                {saving ? <Loader2Icon className="size-4 animate-spin" /> : t('save')}
                            </Button>
                        </DialogFooter>
                    </form>
                </div>
            </DialogContent>
        </Dialog>
    );
}
