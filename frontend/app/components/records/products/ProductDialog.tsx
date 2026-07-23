'use client';

import { FormEvent, useRef, useState } from 'react';
import { useTranslations } from 'next-intl';
import { Loader2Icon } from 'lucide-react';
import {
    TagIcon,
    HashtagIcon,
    CubeIcon,
    BanknotesIcon,
    ReceiptPercentIcon,
} from '@heroicons/react/24/outline';

import {
    ResponsiveDialog,
    ResponsiveDialogContent,
    ResponsiveDialogTitle,
    ResponsiveDialogDescription,
} from '@/components/ui/responsive-dialog';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from '@/components/ui/select';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';
import {
    DialogStatusCover,
    resolveDialogStatus,
    fieldInputClass,
    fieldErrorClass,
    fieldLeadIconClass,
} from '@/components/ui/dialog-status-cover';
import { cn } from '@/lib/utils';
import { isSubmitShortcut } from '@/app/lib/submitShortcut';
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

const selectFieldClass =
    'h-auto w-full rounded-lg border-0 bg-muted py-2 text-sm shadow-none ring-1 ring-border transition focus-visible:ring-2 focus-visible:ring-brand data-[size=default]:h-9';

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
 * server, which owns all monetary arithmetic — this dialog never computes totals. Renders as a
 * centered dialog on desktop and a bottom drawer on mobile.
 */
export default function ProductDialog({ open, onOpenChange, mode, product, onSaved }: Props) {
    const t = useTranslations('ProductDialog');
    const [draft, setDraft] = useState<Draft>(() => toDraft(product));
    const [saving, setSaving] = useState(false);
    const [succeeded, setSucceeded] = useState(false);
    const [nameError, setNameError] = useState(false);
    const nameRef = useRef<HTMLInputElement>(null);

    const patch = (next: Partial<Draft>) => setDraft((prev) => ({ ...prev, ...next }));

    const submit = async () => {
        const name = draft.name.trim();
        if (!name) {
            setNameError(true);
            requestAnimationFrame(() => nameRef.current?.focus());
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

    const handleSubmit = (e: FormEvent) => {
        e.preventDefault();
        if (!saving && !succeeded) submit();
    };

    const status = resolveDialogStatus({ isLoading: saving, hasErrors: nameError, isSuccess: succeeded });

    return (
        <ResponsiveDialog open={open} onOpenChange={(next) => { if (!saving) onOpenChange(next); }}>
            <ResponsiveDialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                <ResponsiveDialogTitle className="sr-only">
                    {mode === 'create' ? t('createTitle') : t('editTitle')}
                </ResponsiveDialogTitle>
                <ResponsiveDialogDescription className="sr-only">{t('description')}</ResponsiveDialogDescription>

                <DialogStatusCover status={status} />

                <div className="px-6 pb-6">
                    <div className="ncd-rise -mt-12 mb-5 flex flex-col gap-2" style={{ animationDelay: '40ms' }}>
                        <h2 className="font-heading text-xl font-semibold leading-none tracking-tight">
                            {mode === 'create' ? t('createTitle') : t('editTitle')}
                        </h2>
                        <p className="text-sm text-muted-foreground">{t('description')}</p>
                    </div>

                    <form
                        onSubmit={handleSubmit}
                        onKeyDown={(e) => {
                            if (isSubmitShortcut(e) && !saving && !succeeded) {
                                e.preventDefault();
                                e.currentTarget.requestSubmit();
                            }
                        }}
                        className="grid gap-5"
                    >
                        <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '90ms' }}>
                            <Label htmlFor="product-name">{t('name')}</Label>
                            <div className="group relative">
                                <TagIcon className={fieldLeadIconClass} />
                                <input
                                    id="product-name"
                                    ref={nameRef}
                                    type="text"
                                    value={draft.name}
                                    maxLength={255}
                                    onChange={(e) => {
                                        patch({ name: e.target.value });
                                        if (nameError) setNameError(false);
                                    }}
                                    className={cn(fieldInputClass, 'pl-9 pr-3', nameError && fieldErrorClass)}
                                    placeholder={t('namePlaceholder')}
                                    aria-invalid={nameError}
                                    aria-describedby={nameError ? 'product-name-error' : undefined}
                                    autoFocus
                                />
                            </div>
                            {nameError && (
                                <p id="product-name-error" className="text-sm text-destructive">{t('nameRequired')}</p>
                            )}
                        </div>

                        <div className="ncd-rise grid grid-cols-2 gap-3" style={{ animationDelay: '140ms' }}>
                            <div className="grid gap-1.5">
                                <Label htmlFor="product-sku">{t('sku')}</Label>
                                <div className="group relative">
                                    <HashtagIcon className={fieldLeadIconClass} />
                                    <input id="product-sku" type="text" value={draft.sku} maxLength={64}
                                        onChange={(e) => patch({ sku: e.target.value })}
                                        placeholder={t('skuPlaceholder')}
                                        className={cn(fieldInputClass, 'pl-9 pr-3')} />
                                </div>
                            </div>
                            <div className="grid gap-1.5">
                                <Label htmlFor="product-unit">{t('unit')}</Label>
                                <div className="group relative">
                                    <CubeIcon className={fieldLeadIconClass} />
                                    <input id="product-unit" type="text" value={draft.unit} maxLength={32}
                                        placeholder={t('unitPlaceholder')}
                                        onChange={(e) => patch({ unit: e.target.value })}
                                        className={cn(fieldInputClass, 'pl-9 pr-3')} />
                                </div>
                            </div>
                        </div>

                        <div className="ncd-rise grid grid-cols-[1fr_120px] gap-3" style={{ animationDelay: '190ms' }}>
                            <div className="grid gap-1.5">
                                <Label htmlFor="product-price">{t('unitPrice')}</Label>
                                <div className="group relative">
                                    <BanknotesIcon className={fieldLeadIconClass} />
                                    <input id="product-price" type="number" min="0" step="0.01" inputMode="decimal"
                                        value={draft.unitPrice}
                                        onChange={(e) => patch({ unitPrice: e.target.value })}
                                        placeholder="0.00"
                                        className={cn(fieldInputClass, 'pl-9 pr-3')} />
                                </div>
                            </div>
                            <div className="grid gap-1.5">
                                <Label htmlFor="product-currency">{t('currency')}</Label>
                                <Select value={draft.currency} onValueChange={(v) => patch({ currency: v })}>
                                    <SelectTrigger id="product-currency" className={selectFieldClass}><SelectValue /></SelectTrigger>
                                    <SelectContent>
                                        {CURRENCY_CODES.map((c) => (
                                            <SelectItem key={c} value={c}>{c}</SelectItem>
                                        ))}
                                    </SelectContent>
                                </Select>
                            </div>
                        </div>

                        <div className="ncd-rise grid grid-cols-2 gap-3" style={{ animationDelay: '240ms' }}>
                            <div className="grid gap-1.5">
                                <Label htmlFor="product-tax">{t('taxRate')}</Label>
                                <div className="group relative">
                                    <ReceiptPercentIcon className={fieldLeadIconClass} />
                                    <input id="product-tax" type="number" min="0" step="0.001" inputMode="decimal"
                                        value={draft.taxRate}
                                        onChange={(e) => patch({ taxRate: e.target.value })}
                                        placeholder="0"
                                        className={cn(fieldInputClass, 'pl-9 pr-3')} />
                                </div>
                            </div>
                            <div className="grid gap-1.5">
                                <Label htmlFor="product-billing">{t('billingFrequency')}</Label>
                                <Select value={draft.billingFrequency}
                                    onValueChange={(v) => patch({ billingFrequency: v as BillingFrequency })}>
                                    <SelectTrigger id="product-billing" className={selectFieldClass}><SelectValue /></SelectTrigger>
                                    <SelectContent>
                                        <SelectItem value="one_time">{t('oneTime')}</SelectItem>
                                        <SelectItem value="recurring">{t('recurring')}</SelectItem>
                                    </SelectContent>
                                </Select>
                            </div>
                        </div>

                        <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '290ms' }}>
                            <Label htmlFor="product-description">{t('descriptionLabel')}</Label>
                            <Textarea id="product-description" value={draft.description} maxLength={1024} rows={3}
                                placeholder={t('descriptionPlaceholder')}
                                onChange={(e) => patch({ description: e.target.value })}
                                className="rounded-lg border-0 bg-muted ring-1 ring-border focus-visible:ring-2 focus-visible:ring-brand dark:bg-muted" />
                        </div>

                        <div className="ncd-rise flex items-center justify-between gap-4 rounded-lg bg-muted/60 px-3.5 py-3 ring-1 ring-border" style={{ animationDelay: '340ms' }}>
                            <div className="grid gap-0.5">
                                <Label htmlFor="product-active" className="cursor-pointer">{t('activeLabel')}</Label>
                                <p className="text-xs text-muted-foreground">{t('activeHelp')}</p>
                            </div>
                            <Switch id="product-active" checked={draft.active}
                                onCheckedChange={(v) => patch({ active: v })} />
                        </div>

                        <div className="ncd-rise mt-1 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end" style={{ animationDelay: '390ms' }}>
                            <Button type="button" variant="outline" disabled={saving}
                                onClick={() => { if (!saving) onOpenChange(false); }}>
                                {t('cancel')}
                            </Button>
                            <Button type="submit" variant="brand" disabled={saving || succeeded}
                                className="min-w-24 shadow-sm transition hover:shadow-md">
                                {saving ? <Loader2Icon className="size-4 animate-spin" /> : t('save')}
                            </Button>
                        </div>
                    </form>
                </div>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}
