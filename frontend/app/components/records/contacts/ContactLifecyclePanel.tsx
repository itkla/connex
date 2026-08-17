'use client';

import { useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useLocale, useTranslations } from 'next-intl';

import SectionHeader from '@/app/components/dashboard/SectionHeader';
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
import { toastError, toastSuccess } from '@/app/lib/toast';
import { formatDateTime } from '@/app/lib/utils';
import { DISQUALIFICATION_REASONS } from '@/app/lib/contactLifecycle';
import type {
    ContactDisqualificationReason,
    ContactLifecycle,
    ContactLifecycleStage,
} from '@/app/lib/types';
import { cn } from '@/lib/utils';

type Props = {
    contactId: number;
    lifecycle: ContactLifecycle;
    /** The contact's first-response SLA clock, absent when it was never put under one (#559). */
    firstResponse?: FirstResponseClock;
    /** Whether the viewer may move the contact; without it the panel is read-only. */
    canEdit: boolean;
    /** Whether a deal is linked to the contact, the prerequisite for marking it converted. */
    hasLinkedDeal: boolean;
    className?: string;
};

const WITHDRAW_VALUE = '__withdraw__';

/** The three SLA timestamps a contact carries; all absent means no clock ever ran. */
export type FirstResponseClock = {
    dueAt?: string | null;
    respondedAt?: string | null;
    breachedAt?: string | null;
};

/**
 * The contact's current lead-lifecycle state and its stage control (issue #559).
 *
 * <p>Connex keeps the lifecycle on the contact rather than in a separate lead record, so this panel
 * sits beside the profile in the record rail. A contact with no stage is not at the start of a
 * pipeline — it is deliberately outside one — and the empty state says so rather than implying the
 * record is incomplete. The server owns the transition rules; this component only offers the moves
 * it was told are legal. Past transitions appear in the record's main timeline, not here, so the
 * lifecycle reads as part of the contact's one history rather than a parallel log.
 */
export default function ContactLifecyclePanel({
    contactId,
    lifecycle,
    firstResponse,
    canEdit,
    hasLinkedDeal,
    className,
}: Props) {
    const t = useTranslations('ContactLifecycle');
    const tsla = useTranslations('ContactResponseSla');
    const locale = useLocale();
    const router = useRouter();
    const currentStage = lifecycle.stage ?? null;
    const currentReason = lifecycle.disqualifiedReason ?? null;
    const currentNotes = lifecycle.qualificationNotes ?? null;
    const currentChangedAt = lifecycle.changedAt ?? null;
    const slaLine = useMemo(() => {
        const dueAt = firstResponse?.dueAt ?? null;
        if (!dueAt) return null;
        const respondedAt = firstResponse?.respondedAt ?? null;
        const breachedAt = firstResponse?.breachedAt ?? null;
        if (respondedAt) {
            return {
                overdue: false,
                text: tsla(breachedAt ? 'respondedLateLine' : 'respondedLine', {
                    when: formatDateTime(respondedAt, locale),
                }),
            };
        }
        if (breachedAt) {
            return {
                overdue: true,
                text: tsla('overdueLine', { when: formatDateTime(breachedAt, locale) }),
            };
        }
        return { overdue: false, text: tsla('dueLine', { when: formatDateTime(dueAt, locale) }) };
    }, [firstResponse, locale, tsla]);

    const [open, setOpen] = useState(false);
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
        setOpen(next);
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
            setOpen(false);
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('toastFailed'));
        } finally {
            setSaving(false);
        }
    };

    return (
        <div className={cn('mt-6', className)}>
            <SectionHeader title={t('title')} />
            <div className="overflow-hidden rounded-2xl border border-border bg-card">
                <div className="flex items-start justify-between gap-4 px-6 py-4 xl:px-4">
                    <div className="min-w-0">
                        <StageBadge stage={currentStage} label={stageLabel(currentStage, t)} />
                        <p className="mt-2 text-xs text-muted-foreground">
                            {currentStage === null
                                ? t('notInLifecycle')
                                : currentChangedAt
                                    ? t('changedAt', { when: formatDateTime(currentChangedAt, locale) })
                                    : ''}
                        </p>
                        {currentReason ? (
                            <p className="mt-1 text-xs text-muted-foreground">
                                {t('reasonLine', { reason: t(`reason.${currentReason}`) })}
                            </p>
                        ) : null}
                        {slaLine ? (
                            <p className={cn(
                                'mt-1 text-xs',
                                slaLine.overdue ? 'text-destructive' : 'text-muted-foreground',
                            )}>
                                {slaLine.text}
                            </p>
                        ) : null}
                    </div>
                    {canEdit ? (
                        <Button
                            variant="outline"
                            size="sm"
                            className="shrink-0"
                            onClick={() => openDialog(true)}
                        >
                            {currentStage === null ? t('startAction') : t('changeAction')}
                        </Button>
                    ) : null}
                </div>

                {currentNotes ? (
                    <p className="border-t border-border px-6 py-4 text-sm text-foreground xl:px-4">
                        {currentNotes}
                    </p>
                ) : null}

            </div>

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
        </div>
    );
}

function stageLabel(
    stage: ContactLifecycleStage | null,
    t: (key: string) => string,
): string {
    return stage === null ? t('stage.none') : t(`stage.${stage}`);
}

const STAGE_TONE: Record<ContactLifecycleStage, string> = {
    NEW: 'border-brand/30 bg-brand-light text-brand-dark',
    WORKING: 'border-brand/30 bg-brand-light text-brand-dark',
    NURTURING: 'border-border bg-muted text-muted-foreground',
    QUALIFIED: 'border-chart-won/30 bg-chart-won/10 text-chart-won',
    DISQUALIFIED: 'border-destructive/30 bg-destructive/10 text-destructive',
    CONVERTED: 'border-chart-won/30 bg-chart-won/10 text-chart-won',
    RECYCLED: 'border-border bg-muted text-muted-foreground',
};

function StageBadge({ stage, label }: { stage: ContactLifecycleStage | null; label: string }) {
    return (
        <span
            className={cn(
                'inline-flex items-center rounded-full border px-2.5 py-0.5 text-[11px] font-medium uppercase tracking-wider',
                stage === null
                    ? 'border-dashed border-border bg-transparent text-muted-foreground'
                    : STAGE_TONE[stage],
            )}
        >
            {label}
        </span>
    );
}
