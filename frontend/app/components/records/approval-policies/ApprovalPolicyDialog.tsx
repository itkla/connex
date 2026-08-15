'use client';

import { useEffect, useState } from 'react';
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
import {
    createApprovalPolicy,
    getActiveWorkspaceMembers,
    previewApprovalPolicyImpact,
    updateApprovalPolicy,
} from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type {
    ApprovalChainMode,
    ApprovalPolicy,
    ApprovalPolicyImpact,
    ApprovalPolicyStep,
    DocumentType,
    SeparationOfDuties,
    UpdateApprovalPolicyPayload,
    WorkspaceMember,
} from '@/app/lib/types';
import ApprovalChainEditor, {
    availableApprovers,
    type ChainStepDraft,
} from './ApprovalChainEditor';

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
    mode: ApprovalChainMode;
    separationOfDuties: SeparationOfDuties;
    steps: ChainStepDraft[];
};

const toStepDraft = (step: ApprovalPolicyStep, index: number): ChainStepDraft => {
    const anyApprover = step.approvers.some((approver) => approver.approverKind === 'any_approver');
    return {
        key: `saved-${step.id ?? index}`,
        id: step.id,
        name: step.name ?? '',
        requiredCount: step.requiredCount,
        kind: anyApprover ? 'any_approver' : 'user',
        userIds: anyApprover
            ? []
            : step.approvers.flatMap((approver) => (approver.userId == null ? [] : [approver.userId])),
    };
};

const toDraft = (policy: ApprovalPolicy | null): Draft => ({
    name: policy?.name ?? '',
    active: policy?.active ?? true,
    documentType: policy?.documentType ?? ALL_TYPES,
    currency: policy?.currency ?? '',
    minTotal: policy?.minTotal != null ? String(policy.minTotal) : '',
    minDiscountPercent: policy?.minDiscountPercent != null ? String(policy.minDiscountPercent) : '',
    mode: policy?.mode ?? 'sequential',
    separationOfDuties: policy?.separationOfDuties ?? 'strict',
    steps: (policy?.steps ?? []).map(toStepDraft),
});

const toStepPayload = (step: ChainStepDraft): ApprovalPolicyStep => ({
    id: step.id,
    name: step.name.trim() === '' ? null : step.name.trim(),
    requiredCount: step.requiredCount,
    approvers:
        step.kind === 'any_approver'
            ? [{ approverKind: 'any_approver' }]
            : step.userIds.map((userId) => ({ approverKind: 'user' as const, userId })),
});

/**
 * Create/edit dialog for a document approval policy: when approval is required, and the approver
 * chain that must clear it. A policy needs a currency whenever a total threshold is set
 * (thresholds are never compared across currencies); the server revalidates everything.
 */
export default function ApprovalPolicyDialog({ open, onOpenChange, policy, onSaved }: Props) {
    const t = useTranslations('ApprovalPolicyDialog');
    const [draft, setDraft] = useState<Draft>(() => toDraft(policy));
    const [saving, setSaving] = useState(false);
    const [wasOpen, setWasOpen] = useState(open);
    const [members, setMembers] = useState<WorkspaceMember[]>([]);
    const [impact, setImpact] = useState<ApprovalPolicyImpact | null>(null);

    if (open !== wasOpen) {
        setWasOpen(open);
        if (open) {
            setDraft(toDraft(policy));
            setImpact(null);
        }
    }

    useEffect(() => {
        if (!open) return;
        let cancelled = false;
        getActiveWorkspaceMembers()
            .then((all) => {
                if (!cancelled) setMembers(all.filter((member) => member.status !== 'pending'));
            })
            .catch(() => {
                if (!cancelled) setMembers([]);
            });
        return () => {
            cancelled = true;
        };
    }, [open]);

    const set = <K extends keyof Draft>(key: K, value: Draft[K]) =>
        setDraft((prev) => ({ ...prev, [key]: value }));

    const minTotalNeedsCurrency = draft.minTotal.trim() !== '' && draft.currency.trim() === '';
    const chainIsValid = draft.steps.every(
        (step) =>
            (step.kind === 'any_approver' || step.userIds.length > 0)
            && step.requiredCount <= availableApprovers(step),
    );
    const canSave = draft.name.trim() !== '' && !minTotalNeedsCurrency && chainIsValid && !saving;

    const buildPayload = (): UpdateApprovalPolicyPayload => ({
        name: draft.name.trim(),
        active: draft.active,
        documentType: isDocumentType(draft.documentType) ? draft.documentType : null,
        currency: draft.currency.trim() === '' ? null : draft.currency.trim().toUpperCase(),
        minTotal: draft.minTotal.trim() === '' ? null : Number(draft.minTotal),
        minDiscountPercent: draft.minDiscountPercent.trim() === '' ? null : Number(draft.minDiscountPercent),
        mode: draft.mode,
        separationOfDuties: draft.separationOfDuties,
        steps: draft.steps.map(toStepPayload),
    });

    const commit = async (payload: UpdateApprovalPolicyPayload) => {
        setSaving(true);
        try {
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

    /**
     * Tightening a policy terminates the approvals already pending under it, so the count and the
     * consequence are disclosed before anything is written. The preview is advisory: the server
     * re-runs the same classification under the policy lock and still refuses an unconfirmed
     * tightening, so a request that races another edit fails rather than invalidating silently.
     */
    const save = async () => {
        if (!canSave) return;
        const payload = buildPayload();
        if (!policy) {
            await commit(payload);
            return;
        }
        setSaving(true);
        let preview: ApprovalPolicyImpact | null = null;
        try {
            preview = await previewApprovalPolicyImpact(policy.id, payload);
        } catch {
            preview = null;
        } finally {
            setSaving(false);
        }
        if (preview && preview.changeClass === 'TIGHTEN' && preview.pendingApprovalCount > 0) {
            setImpact(preview);
            return;
        }
        await commit(payload);
    };

    const confirmInvalidation = async () => {
        setImpact(null);
        await commit({ ...buildPayload(), confirmInvalidation: true });
    };

    if (impact) {
        return (
            <Dialog open={open} onOpenChange={onOpenChange}>
                <DialogContent>
                    <DialogHeader>
                        <DialogTitle>{t('impactTitle')}</DialogTitle>
                        <DialogDescription>
                            {t('impactBody', { count: impact.pendingApprovalCount })}
                        </DialogDescription>
                    </DialogHeader>
                    <div className="max-h-[50dvh] space-y-3 overflow-y-auto pr-1">
                        <p className="text-sm text-muted-foreground">{t('impactConsequence')}</p>
                        <ul className="flex flex-col gap-2">
                            {impact.affected.map((item) => (
                                <li
                                    key={item.documentId}
                                    className="rounded-xl border border-border px-3 py-2 text-sm"
                                >
                                    <p className="font-medium">{item.documentTitle}</p>
                                    <p className="text-xs text-muted-foreground">
                                        {t('impactItemMeta', {
                                            deal: item.dealName,
                                            version: item.version,
                                            requester: item.requestedByName,
                                        })}
                                    </p>
                                </li>
                            ))}
                        </ul>
                        {impact.pendingApprovalCount > impact.affected.length && (
                            <p className="text-xs text-muted-foreground">
                                {t('impactMore', {
                                    count: impact.pendingApprovalCount - impact.affected.length,
                                })}
                            </p>
                        )}
                    </div>
                    <DialogFooter>
                        <Button variant="outline" disabled={saving} onClick={() => setImpact(null)}>
                            {t('impactBack')}
                        </Button>
                        <Button variant="destructive" disabled={saving} onClick={confirmInvalidation}>
                            {saving ? <Loader2Icon className="size-4 animate-spin" /> : t('impactConfirm')}
                        </Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>
        );
    }

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>{policy ? t('editTitle') : t('newTitle')}</DialogTitle>
                    <DialogDescription>{t('description')}</DialogDescription>
                </DialogHeader>
                <div className="max-h-[60dvh] space-y-4 overflow-y-auto pr-1">
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
                    <div className="space-y-3 border-t border-border pt-4">
                        <p className="text-sm font-medium">{t('chainTitle')}</p>
                        <ApprovalChainEditor
                            mode={draft.mode}
                            onModeChange={(mode) => set('mode', mode)}
                            separationOfDuties={draft.separationOfDuties}
                            onSeparationOfDutiesChange={(value) => set('separationOfDuties', value)}
                            steps={draft.steps}
                            onStepsChange={(steps) => set('steps', steps)}
                            members={members}
                            disabled={saving}
                        />
                    </div>
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
