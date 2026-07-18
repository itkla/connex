'use client';

import { useState } from 'react';
import { useTranslations } from 'next-intl';
import { Loader2Icon } from 'lucide-react';

import { Button } from '@/components/ui/button';
import {
    Dialog,
    DialogContent,
    DialogHeader,
    DialogTitle,
    DialogDescription,
    DialogFooter,
    DialogClose,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from '@/components/ui/select';
import { createApprovalPolicy, updateApprovalPolicy } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type { ApprovalPolicy, DocumentType } from '@/app/lib/types';

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    policy: ApprovalPolicy | null;
    onSaved: (policy: ApprovalPolicy, isNew: boolean) => void;
};

const DOCUMENT_TYPES: DocumentType[] = ['quote', 'proposal', 'order_form', 'contract'];
const ALL_TYPES = 'all';

const isDocumentType = (value: string): value is DocumentType =>
    (DOCUMENT_TYPES as string[]).includes(value);

type Draft = {
    name: string;
    active: boolean;
    documentType: string;
    currency: string;
    minTotal: string;
    minDiscountPercent: string;
};

const toDraft = (policy: ApprovalPolicy | null): Draft => ({
    name: policy?.name ?? '',
    active: policy?.active ?? true,
    documentType: policy?.documentType ?? ALL_TYPES,
    currency: policy?.currency ?? '',
    minTotal: policy?.minTotal != null ? String(policy.minTotal) : '',
    minDiscountPercent: policy?.minDiscountPercent != null ? String(policy.minDiscountPercent) : '',
});

/**
 * Create/edit dialog for a document approval policy. A policy needs a currency whenever a total
 * threshold is set (thresholds are never compared across currencies); the server revalidates.
 */
export default function ApprovalPolicyDialog({ open, onOpenChange, policy, onSaved }: Props) {
    const t = useTranslations('ApprovalPolicyDialog');
    const [draft, setDraft] = useState<Draft>(() => toDraft(policy));
    const [saving, setSaving] = useState(false);
    const [wasOpen, setWasOpen] = useState(open);

    if (open !== wasOpen) {
        setWasOpen(open);
        if (open) setDraft(toDraft(policy));
    }

    const set = <K extends keyof Draft>(key: K, value: Draft[K]) =>
        setDraft((prev) => ({ ...prev, [key]: value }));

    const minTotalNeedsCurrency = draft.minTotal.trim() !== '' && draft.currency.trim() === '';
    const canSave = draft.name.trim() !== '' && !minTotalNeedsCurrency && !saving;

    const save = async () => {
        if (!canSave) return;
        setSaving(true);
        try {
            const payload = {
                name: draft.name.trim(),
                active: draft.active,
                documentType: isDocumentType(draft.documentType) ? draft.documentType : null,
                currency: draft.currency.trim() === '' ? null : draft.currency.trim().toUpperCase(),
                minTotal: draft.minTotal.trim() === '' ? null : Number(draft.minTotal),
                minDiscountPercent: draft.minDiscountPercent.trim() === '' ? null : Number(draft.minDiscountPercent),
            };
            const saved = policy
                ? await updateApprovalPolicy(policy.id, payload)
                : await createApprovalPolicy(payload);
            toastSuccess(policy ? t('updated') : t('created'));
            onSaved(saved, policy === null);
            onOpenChange(false);
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('saveFailed'));
        } finally {
            setSaving(false);
        }
    };

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>{policy ? t('editTitle') : t('newTitle')}</DialogTitle>
                    <DialogDescription>{t('description')}</DialogDescription>
                </DialogHeader>
                <div className="space-y-4">
                    <div className="space-y-2">
                        <Label htmlFor="policy-name">{t('nameLabel')}</Label>
                        <Input
                            id="policy-name"
                            value={draft.name}
                            maxLength={255}
                            placeholder={t('namePlaceholder')}
                            onChange={(e) => set('name', e.target.value)}
                            disabled={saving}
                        />
                    </div>
                    <div className="grid grid-cols-2 gap-3">
                        <div className="space-y-2">
                            <Label>{t('typeLabel')}</Label>
                            <Select value={draft.documentType} onValueChange={(v) => set('documentType', v)}>
                                <SelectTrigger aria-label={t('typeLabel')} className="w-full"><SelectValue /></SelectTrigger>
                                <SelectContent>
                                    <SelectItem value={ALL_TYPES}>{t('typeAll')}</SelectItem>
                                    {DOCUMENT_TYPES.map((type) => (
                                        <SelectItem key={type} value={type}>{t(`type_${type}`)}</SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
                        </div>
                        <div className="space-y-2">
                            <Label htmlFor="policy-currency">{t('currencyLabel')}</Label>
                            <Input
                                id="policy-currency"
                                value={draft.currency}
                                maxLength={8}
                                placeholder={t('currencyPlaceholder')}
                                onChange={(e) => set('currency', e.target.value)}
                                disabled={saving}
                                aria-invalid={minTotalNeedsCurrency || undefined}
                            />
                        </div>
                    </div>
                    <div className="grid grid-cols-2 gap-3">
                        <div className="space-y-2">
                            <Label htmlFor="policy-min-total">{t('minTotalLabel')}</Label>
                            <Input
                                id="policy-min-total"
                                type="number"
                                min={0}
                                step="0.01"
                                value={draft.minTotal}
                                placeholder={t('minTotalPlaceholder')}
                                onChange={(e) => set('minTotal', e.target.value)}
                                disabled={saving}
                            />
                        </div>
                        <div className="space-y-2">
                            <Label htmlFor="policy-min-discount">{t('minDiscountLabel')}</Label>
                            <Input
                                id="policy-min-discount"
                                type="number"
                                min={0}
                                max={100}
                                step="0.1"
                                value={draft.minDiscountPercent}
                                placeholder={t('minDiscountPlaceholder')}
                                onChange={(e) => set('minDiscountPercent', e.target.value)}
                                disabled={saving}
                            />
                        </div>
                    </div>
                    {minTotalNeedsCurrency ? (
                        <p className="text-xs text-destructive">{t('currencyRequired')}</p>
                    ) : (
                        <p className="text-xs text-muted-foreground">{t('thresholdHint')}</p>
                    )}
                    <div className="flex items-center justify-between rounded-xl border border-border px-3 py-2.5">
                        <Label htmlFor="policy-active" className="cursor-pointer">{t('activeLabel')}</Label>
                        <Switch
                            id="policy-active"
                            checked={draft.active}
                            onCheckedChange={(checked) => set('active', checked)}
                            disabled={saving}
                        />
                    </div>
                </div>
                <DialogFooter>
                    <DialogClose asChild>
                        <Button variant="outline" disabled={saving}>{t('cancel')}</Button>
                    </DialogClose>
                    <Button variant="brand" disabled={!canSave} onClick={save}>
                        {saving ? <Loader2Icon className="size-4 animate-spin" /> : t('save')}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}
