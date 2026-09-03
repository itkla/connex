'use client';

import { useEffect, useMemo, useState } from 'react';
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
import {
    getDisqualificationReasons,
    updateContactLifecycle,
    withdrawContactLifecycle,
} from '@/app/lib/api';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import { toastSuccess } from '@/app/lib/toast';
import type {
    ContactDisqualificationReason,
    ContactLifecycle,
    ContactLifecycleStage,
    DisqualificationReason,
} from '@/app/lib/types';
import {
    isBuiltInDisqualificationReason,
    isCanonicalDisqualificationReasonCode,
} from '@/app/lib/contactLifecycle';

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
    ...props
}: Props) {
    const { activeWorkspaceId } = useWorkspace();
    return <WorkspaceContactStageDialog key={activeWorkspaceId ?? 'none'} {...props} />;
}

function WorkspaceContactStageDialog({
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
    const [reason, setReason] = useState<ContactDisqualificationReason | ''>(
        currentStage === 'DISQUALIFIED' ? (currentReason ?? '') : '',
    );
    const [note, setNote] = useState(currentNotes ?? '');
    const [reasons, setReasons] = useState<DisqualificationReason[] | null>(null);
    const [reasonsUnavailable, setReasonsUnavailable] = useState(false);

    useEffect(() => {
        if (!open) return;
        let cancelled = false;
        getDisqualificationReasons(true)
            .then((loaded) => {
                if (cancelled) return;
                setReasons(loaded);
                setReasonsUnavailable(false);
            })
            .catch(() => {
                if (cancelled) return;
                setReasons(null);
                setReasonsUnavailable(true);
            });
        return () => {
            cancelled = true;
        };
    }, [open]);

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
    const availableReasons = useMemo(
        () => reasons?.filter((candidate) =>
            isCanonicalDisqualificationReasonCode(candidate.code)
            && (candidate.archivedAt === null || candidate.code === currentReason)) ?? [],
        [currentReason, reasons],
    );
    const selectedReason = isCanonicalDisqualificationReasonCode(reason)
        ? availableReasons.find((candidate) => candidate.code === reason) ?? null
        : null;
    const submittable = stage !== ''
        && (!disqualifying || (
            selectedReason !== null
            && (!selectedReason.requiresNote || note.trim() !== '')
        ));

    const openDialog = (next: boolean) => {
        if (!next && saving) return;
        if (next) {
            setStage('');
            setReason(currentStage === 'DISQUALIFIED' ? (currentReason ?? '') : '');
            setNote(currentNotes ?? '');
            setReasonsUnavailable(false);
        }
        onOpenChange(next);
    };

    const submit = async () => {
        if (!submittable || saving) return;
        const submittedReason = disqualifying && isCanonicalDisqualificationReasonCode(reason)
            ? reason
            : null;
        if (disqualifying && submittedReason === null) return;
        setSaving(true);
        try {
            if (withdrawing) {
                await withdrawContactLifecycle(contactId, note.trim() || undefined);
            } else {
                await updateContactLifecycle(contactId, {
                    stage: stage as ContactLifecycleStage,
                    reason: submittedReason,
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
                                onValueChange={setReason}
                                disabled={saving}
                            >
                                <SelectTrigger id="contact-lifecycle-reason">
                                    <SelectValue placeholder={t('reasonPlaceholder')} />
                                </SelectTrigger>
                                <SelectContent>
                                    {availableReasons.map((candidate) => (
                                        <SelectItem key={candidate.code} value={candidate.code}>
                                            {candidate.label ?? (
                                                isBuiltInDisqualificationReason(candidate.code)
                                                    ? t(`reason.${candidate.code}`)
                                                    : candidate.code
                                            )}
                                        </SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
                            {reasons === null && !reasonsUnavailable ? (
                                <p className="text-xs text-muted-foreground">{t('reasonsLoading')}</p>
                            ) : null}
                            {reasonsUnavailable ? (
                                <p className="text-xs text-destructive">{t('reasonsUnavailable')}</p>
                            ) : null}
                            {selectedReason?.requiresNote ? (
                                <p className="text-xs text-muted-foreground">{t('reasonNoteRequired')}</p>
                            ) : null}
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
