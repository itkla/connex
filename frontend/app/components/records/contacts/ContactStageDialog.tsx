'use client';

import { useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';

import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import {
    ResponsiveDialog,
    ResponsiveDialogClose,
    ResponsiveDialogContent,
    ResponsiveDialogDescription,
    ResponsiveDialogFooter,
    ResponsiveDialogHeader,
    ResponsiveDialogTitle,
} from '@/components/ui/responsive-dialog';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from '@/components/ui/select';
import { Textarea } from '@/components/ui/textarea';
import { updateContactLifecycle, withdrawContactLifecycle } from '@/app/lib/api';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { toastSuccess } from '@/app/lib/toast';
import { DISQUALIFICATION_REASONS } from '@/app/lib/contactLifecycle';
import type {
    ContactDisqualificationReason,
    ContactLifecycle,
    ContactLifecycleStage,
} from '@/app/lib/types';

const WITHDRAW_VALUE = '__withdraw__';

type Props = {
    contactId: number;
    lifecycle: ContactLifecycle;
    /** Whether a deal is linked, the prerequisite for marking the contact converted. */
    hasLinkedDeal: boolean;
    open: boolean;
    onOpenChange: (open: boolean) => void;
};

/**
 * Moving a contact through the lead lifecycle (issue #559).
 *
 * <p>The server owns the transition rules and this dialog offers only the moves it was told are
 * legal, including the qualification gate — a stage the required criteria block is not advertised,
 * so the control never presents a move whose only outcome is an error.
 */
export default function ContactStageDialog({
    contactId,
    lifecycle,
    hasLinkedDeal,
    open,
    onOpenChange,
}: Props) {
    const t = useTranslations('ContactLifecycle');
    const showApiError = useApiErrorToast('ContactLifecycle');
    const router = useRouter();
    const currentStage = lifecycle.stage ?? null;
    const currentReason = lifecycle.disqualifiedReason ?? null;
    const currentNotes = lifecycle.qualificationNotes ?? null;

    const [saving, setSaving] = useState(false);
    const [stage, setStage] = useState<string>('');
    const [reason, setReason] = useState<ContactDisqualificationReason | ''>('');
    const [note, setNote] = useState('');

    const stageOptions = useMemo(() => {
        const moves = lifecycle.allowedTransitions
            .filter((value) => value !== 'CONVERTED' || hasLinkedDeal)
            .map((value) => ({ value: value as string, label: t(`stage.${value}`) }));
        if (currentStage !== null) {
            moves.unshift({
                value: currentStage as string,
                label: t('stayOption', { stage: t(`stage.${currentStage}`) }),
            });
        }
        return moves;
    }, [lifecycle.allowedTransitions, currentStage, hasLinkedDeal, t]);

    const disqualifying = stage === 'DISQUALIFIED';
    const withdrawing = stage === WITHDRAW_VALUE;
    const submittable = stage !== ''
        && (!disqualifying || (reason !== '' && (reason !== 'OTHER' || note.trim() !== '')));

    const openDialog = (next: boolean) => {
        if (!next && saving) return;
        if (next) {
            setStage('');
            setReason(currentStage === 'DISQUALIFIED' ? (currentReason ?? '') : '');
            setNote(currentNotes ?? '');
        }
        onOpenChange(next);
    };

    const submit = async () => {
        if (!submittable || saving) return;
        setSaving(true);
        try {
            if (withdrawing) {
                await withdrawContactLifecycle(contactId, note.trim() || undefined);
            } else {
                await updateContactLifecycle(contactId, {
                    stage: stage as ContactLifecycleStage,
                    reason: disqualifying ? (reason as ContactDisqualificationReason) : null,
                    note: note.trim() || null,
                });
            }
            toastSuccess(t('toastSaved'));
            onOpenChange(false);
            router.refresh();
        } catch (err) {
            showApiError(err, 'toastFailed');
        } finally {
            setSaving(false);
        }
    };

    return (
        <ResponsiveDialog open={open} onOpenChange={openDialog}>
            <ResponsiveDialogContent className="sm:max-w-md">
                <ResponsiveDialogHeader>
                    <ResponsiveDialogTitle>{t('dialogTitle')}</ResponsiveDialogTitle>
                    <ResponsiveDialogDescription>{t('dialogDescription')}</ResponsiveDialogDescription>
                </ResponsiveDialogHeader>

                <div className="flex flex-col gap-4 px-6 pb-2 sm:px-0">
                    <div className="flex flex-col gap-2">
                        <Label htmlFor="contact-lifecycle-stage">{t('stageLabel')}</Label>
                        <Select value={stage} onValueChange={setStage} disabled={saving}>
                            <SelectTrigger id="contact-lifecycle-stage">
                                <SelectValue placeholder={t('stagePlaceholder')} />
                            </SelectTrigger>
                            <SelectContent>
                                {stageOptions.map((option) => (
                                    <SelectItem key={option.value} value={option.value}>
                                        {option.label}
                                    </SelectItem>
                                ))}
                                {currentStage === null ? null : (
                                    <SelectItem value={WITHDRAW_VALUE}>{t('withdrawOption')}</SelectItem>
                                )}
                            </SelectContent>
                        </Select>
                        {withdrawing ? (
                            <p className="text-xs text-muted-foreground">{t('withdrawHint')}</p>
                        ) : null}
                    </div>

                    {disqualifying ? (
                        <div className="flex flex-col gap-2">
                            <Label htmlFor="contact-lifecycle-reason">{t('reasonLabel')}</Label>
                            <Select
                                value={reason}
                                onValueChange={(value) => setReason(value as ContactDisqualificationReason)}
                                disabled={saving}
                            >
                                <SelectTrigger id="contact-lifecycle-reason">
                                    <SelectValue placeholder={t('reasonPlaceholder')} />
                                </SelectTrigger>
                                <SelectContent>
                                    {DISQUALIFICATION_REASONS.map((value) => (
                                        <SelectItem key={value} value={value}>
                                            {t(`reason.${value}`)}
                                        </SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
                        </div>
                    ) : null}

                    <div className="flex flex-col gap-2">
                        <Label htmlFor="contact-lifecycle-note">{t('noteLabel')}</Label>
                        <Textarea
                            id="contact-lifecycle-note"
                            value={note}
                            maxLength={2000}
                            rows={3}
                            disabled={saving}
                            onChange={(event) => setNote(event.target.value)}
                            placeholder={t('notePlaceholder')}
                        />
                    </div>
                </div>

                <ResponsiveDialogFooter>
                    <ResponsiveDialogClose asChild>
                        <Button type="button" variant="outline" disabled={saving}>
                            {t('cancel')}
                        </Button>
                    </ResponsiveDialogClose>
                    <Button onClick={() => void submit()} disabled={!submittable || saving}>
                        {t('save')}
                    </Button>
                </ResponsiveDialogFooter>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}
