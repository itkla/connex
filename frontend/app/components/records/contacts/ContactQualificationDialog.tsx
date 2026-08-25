'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';

import { Button } from '@/components/ui/button';
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
import { answerContactQualification } from '@/app/lib/api';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import type {
    ContactQualification,
    QualificationAnswer,
    QualificationDimension,
} from '@/app/lib/types';

type Props = {
    contactId: number;
    qualification: ContactQualification;
    open: boolean;
    onOpenChange: (open: boolean) => void;
    canEdit: boolean;
};

const DIMENSIONS: QualificationDimension[] = ['FIT', 'ENGAGEMENT'];
const ANSWERS: QualificationAnswer[] = ['MET', 'NOT_MET', 'UNKNOWN'];
const UNANSWERED_VALUE = '__unanswered__';

/** Narrows a select value to the closed answer vocabulary instead of asserting it. */
function asAnswer(value: string): QualificationAnswer | null {
    return ANSWERS.find((answer) => answer === value) ?? null;
}

/**
 * Answering the workspace's qualification criteria for one contact (issue #559).
 *
 * <p>This is a task, not a state readout, so it lives in a dialog rather than the record rail. A
 * workspace may configure up to fifty criteria, and fifty label-and-control rows cannot be answered
 * in a 256px column; here each question gets its full width, and the rail keeps only the resulting
 * scores and whatever is blocking qualification.
 */
export default function ContactQualificationDialog({
    contactId,
    qualification,
    open,
    onOpenChange,
    canEdit,
}: Props) {
    const t = useTranslations('ContactQualification');
    const showApiError = useApiErrorToast('ContactQualification');
    const router = useRouter();
    const [saving, setSaving] = useState<number | null>(null);

    const answer = async (criterionId: number, value: string) => {
        const narrowed = asAnswer(value);
        if (value !== UNANSWERED_VALUE && narrowed === null) return;
        setSaving(criterionId);
        try {
            await answerContactQualification(contactId, {
                criterionId,
                answer: value === UNANSWERED_VALUE ? null : narrowed,
            });
            router.refresh();
        } catch (err) {
            showApiError(err, 'toastFailed');
        } finally {
            setSaving(null);
        }
    };

    return (
        <ResponsiveDialog open={open} onOpenChange={onOpenChange}>
            <ResponsiveDialogContent className="sm:max-w-lg">
                <ResponsiveDialogHeader>
                    <ResponsiveDialogTitle>{t('title')}</ResponsiveDialogTitle>
                    <ResponsiveDialogDescription>{t('dialogDescription')}</ResponsiveDialogDescription>
                </ResponsiveDialogHeader>

                <div className="max-h-[60vh] overflow-y-auto px-6 pb-2 sm:px-0">
                    {DIMENSIONS.map((dimension) => {
                        const criteria = qualification.criteria.filter(
                            (criterion) => criterion.dimension === dimension,
                        );
                        if (criteria.length === 0) return null;
                        return (
                            <section key={dimension} className="mb-4 last:mb-0">
                                <h3 className="text-xs font-medium text-muted-foreground">
                                    {t(`dimension.${dimension}`)}
                                </h3>
                                <ul className="mt-2 divide-y divide-border rounded-xl border border-border">
                                    {criteria.map((criterion) => (
                                        <li
                                            key={criterion.criterionId}
                                            className="flex items-center justify-between gap-3 px-3 py-2"
                                        >
                                            <div className="min-w-0">
                                                <p className="text-sm text-foreground">{criterion.label}</p>
                                                {criterion.required ? (
                                                    <p className="text-xs text-muted-foreground">
                                                        {t('required')}
                                                    </p>
                                                ) : null}
                                            </div>
                                            <Select
                                                value={criterion.answer ?? UNANSWERED_VALUE}
                                                onValueChange={(value) =>
                                                    void answer(criterion.criterionId, value)}
                                                disabled={!canEdit || saving === criterion.criterionId}
                                            >
                                                <SelectTrigger
                                                    size="sm"
                                                    className="w-36 shrink-0"
                                                    aria-label={criterion.label}
                                                >
                                                    <SelectValue />
                                                </SelectTrigger>
                                                <SelectContent>
                                                    <SelectItem value={UNANSWERED_VALUE}>
                                                        {t('answer.unanswered')}
                                                    </SelectItem>
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
                            </section>
                        );
                    })}
                </div>

                <ResponsiveDialogFooter>
                    <ResponsiveDialogClose asChild>
                        <Button type="button" variant="outline">{t('done')}</Button>
                    </ResponsiveDialogClose>
                </ResponsiveDialogFooter>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}
