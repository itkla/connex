'use client';

import { useCallback, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';

import SectionHeader from '@/app/components/dashboard/SectionHeader';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from '@/components/ui/select';
import { answerContactQualification } from '@/app/lib/api';
import { toastError } from '@/app/lib/toast';
import type {
    ContactQualification,
    ContactQualificationScore,
    QualificationAnswer,
    QualificationDimension,
} from '@/app/lib/types';
import { cn } from '@/lib/utils';

type Props = {
    contactId: number;
    qualification: ContactQualification;
    /** Whether the viewer may answer; without it the panel is a read-only record of the assessment. */
    canEdit: boolean;
    className?: string;
};

const DIMENSIONS: QualificationDimension[] = ['FIT', 'ENGAGEMENT'];
const ANSWERS: QualificationAnswer[] = ['MET', 'NOT_MET', 'UNKNOWN'];
const UNANSWERED_VALUE = '__unanswered__';

/**
 * The contact's qualification: the workspace's own questions, this contact's answers, and the two
 * deterministic scores (issue #559).
 *
 * <p>Fit and engagement are shown side by side and never blended into one number — a contact that
 * fits perfectly but has gone quiet and one that talks constantly but could never buy are different
 * problems, and a single figure hides which one you are reading.
 *
 * <p>A dimension with no criteria configured shows a dash rather than 0%: the workspace has not
 * judged this contact poorly on an axis it never defined.
 */
export default function ContactQualificationPanel({
    contactId,
    qualification,
    canEdit,
    className,
}: Props) {
    const t = useTranslations('ContactQualification');
    const router = useRouter();
    const [saving, setSaving] = useState<number | null>(null);

    const byDimension = useMemo(() => new Map(
        DIMENSIONS.map((dimension) => [
            dimension,
            qualification.criteria.filter((criterion) => criterion.dimension === dimension),
        ]),
    ), [qualification.criteria]);

    const scoreOf = useCallback(
        (dimension: QualificationDimension): ContactQualificationScore | undefined =>
            qualification.scores.find((score) => score.dimension === dimension),
        [qualification.scores],
    );

    const answer = async (criterionId: number, value: string) => {
        setSaving(criterionId);
        try {
            await answerContactQualification(contactId, {
                criterionId,
                answer: value === UNANSWERED_VALUE ? null : (value as QualificationAnswer),
            });
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('toastFailed'));
        } finally {
            setSaving(null);
        }
    };

    if (qualification.criteria.length === 0) {
        return (
            <div className={cn('mt-6', className)}>
                <SectionHeader title={t('title')} />
                <div className="overflow-hidden rounded-2xl border border-border bg-card px-6 py-4 xl:px-4">
                    <p className="text-xs text-muted-foreground">{t('noCriteria')}</p>
                </div>
            </div>
        );
    }

    return (
        <div className={cn('mt-6', className)}>
            <SectionHeader title={t('title')} />
            <div className="overflow-hidden rounded-2xl border border-border bg-card">
                <div className="grid grid-cols-2 gap-px border-b border-border bg-border">
                    {DIMENSIONS.map((dimension) => {
                        const score = scoreOf(dimension);
                        return (
                            <div key={dimension} className="bg-card px-6 py-4 xl:px-4">
                                <p className="text-xs text-muted-foreground">
                                    {t(`dimension.${dimension}`)}
                                </p>
                                <p className="mt-1 text-2xl font-semibold tabular-nums text-foreground">
                                    {score?.percent === null || score?.percent === undefined
                                        ? '—'
                                        : `${score.percent}%`}
                                </p>
                                {score && score.unansweredCount > 0 ? (
                                    <p className="mt-1 text-xs text-muted-foreground">
                                        {t('unanswered', { count: score.unansweredCount })}
                                    </p>
                                ) : null}
                            </div>
                        );
                    })}
                </div>

                <ul className="divide-y divide-border">
                    {DIMENSIONS.flatMap((dimension) => byDimension.get(dimension) ?? []).map((criterion) => (
                        <li
                            key={criterion.criterionId}
                            className="flex items-center justify-between gap-3 px-6 py-3 xl:px-4"
                        >
                            <div className="min-w-0">
                                <p className="truncate text-sm text-foreground">{criterion.label}</p>
                                <p className="text-xs text-muted-foreground">
                                    {t(`dimension.${criterion.dimension}`)}
                                    {criterion.required ? ` · ${t('required')}` : ''}
                                </p>
                            </div>
                            <Select
                                value={criterion.answer ?? UNANSWERED_VALUE}
                                onValueChange={(value) => void answer(criterion.criterionId, value)}
                                disabled={!canEdit || saving === criterion.criterionId}
                            >
                                <SelectTrigger size="sm" className="w-36 shrink-0" aria-label={criterion.label}>
                                    <SelectValue />
                                </SelectTrigger>
                                <SelectContent>
                                    <SelectItem value={UNANSWERED_VALUE}>{t('answer.unanswered')}</SelectItem>
                                    {ANSWERS.map((value) => (
                                        <SelectItem key={value} value={value}>
                                            {t(`answer.${value}`)}
                                        </SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
                        </li>
                    ))}
                </ul>

                {qualification.qualifiable ? null : (
                    <p className="border-t border-border px-6 py-3 text-xs text-muted-foreground xl:px-4">
                        {t('blocked')}
                    </p>
                )}
            </div>
        </div>
    );
}
