'use client';

import { useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { Loader2Icon } from 'lucide-react';

import { Label } from '@/components/ui/label';
import { fieldInputClass } from '@/components/ui/dialog-status-cover';
import { cn } from '@/lib/utils';
import { createActivity, isFieldError } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { useFieldErrors } from '@/app/hooks/useFieldErrors';
import { ACTIVITY_TYPES, ActivityTypePicker, type ActivityType } from '@/app/components/activity/activities/activityTypes';
import type { CreateDefaults } from '@/app/lib/actions/types';

import QuickFormContextChip from './QuickFormContextChip';
import QuickFormFooter from './QuickFormFooter';

/**
 * Minimal in-panel activity capture. Calls the same {@link createActivity} implementation the full
 * dialog uses, logged by the current user at the current time and carrying any person/deal context. The
 * "More details" hatch escalates to the full dialog for timestamp and linking.
 */
export default function ActivityQuickForm({
    defaults,
    currentUserId,
    onCreated,
    onMoreDetails,
}: {
    defaults?: CreateDefaults;
    currentUserId: number;
    onCreated: () => void;
    onMoreDetails: (draft: { type: ActivityType; subject: string; notes: string }) => void;
}) {
    const router = useRouter();
    const t = useTranslations('Actions');
    const ta = useTranslations('ActivityCreateDialog');
    const submittingRef = useRef(false);

    const [type, setType] = useState<ActivityType>(ACTIVITY_TYPES[0]);
    const [subject, setSubject] = useState('');
    const [notes, setNotes] = useState('');
    const [submitting, setSubmitting] = useState(false);
    const { fieldErrors, reset: resetFieldErrors, clearError, captureFieldErrors } = useFieldErrors();

    const handleSubmit = async (e: React.SubmitEvent<HTMLFormElement>) => {
        e.preventDefault();
        if (submittingRef.current) return;
        resetFieldErrors();
        submittingRef.current = true;
        setSubmitting(true);
        try {
            await createActivity({
                type,
                subject: subject.trim(),
                notes: notes.trim() || undefined,
                createdById: currentUserId,
                personId: defaults?.personId,
                dealId: defaults?.dealId,
            });
            toastSuccess(t('feedback.activityCreated'));
            router.refresh();
            onCreated();
        } catch (err) {
            if (captureFieldErrors(err)) {
                if (isFieldError(err)) {
                    const firstKey = Object.keys(err.fieldErrors)[0];
                    if (firstKey) requestAnimationFrame(() => document.getElementById(`quick-activity-${firstKey}`)?.focus());
                }
                return;
            }
            toastError(err instanceof Error ? err.message : t('feedback.createFailed'));
        } finally {
            submittingRef.current = false;
            setSubmitting(false);
        }
    };

    return (
        <form onSubmit={handleSubmit} className="grid gap-4">
            <QuickFormContextChip defaults={defaults} />
            <div className="grid gap-1.5">
                <Label>{ta('typeLabel')}</Label>
                <ActivityTypePicker
                    value={type}
                    onChange={setType}
                    getLabel={(ty) => ta(`type${ty}` as 'typeCall')}
                    disabled={submitting}
                />
            </div>
            <div className="grid gap-1.5">
                <Label htmlFor="quick-activity-subject">{ta('subjectLabel')}</Label>
                <input
                    id="quick-activity-subject"
                    value={subject}
                    onChange={(e) => {
                        setSubject(e.target.value);
                        clearError('subject');
                    }}
                    placeholder={ta('subjectPlaceholder')}
                    autoFocus
                    aria-invalid={Boolean(fieldErrors.subject)}
                    className={cn(fieldInputClass, 'px-3')}
                />
                {fieldErrors.subject && <p className="text-sm text-destructive">{fieldErrors.subject}</p>}
            </div>
            <div className="grid gap-1.5">
                <Label htmlFor="quick-activity-notes">{ta('notesLabel')}</Label>
                <textarea
                    id="quick-activity-notes"
                    value={notes}
                    onChange={(e) => setNotes(e.target.value)}
                    rows={2}
                    className={cn(fieldInputClass, 'min-h-16 resize-none px-3 py-2')}
                />
            </div>

            <QuickFormFooter
                onMoreDetails={() => onMoreDetails({ type, subject, notes })}
                submitDisabled={submitting || !subject.trim()}
            >
                {submitting ? <Loader2Icon className="size-4 animate-spin" /> : t('quickCreate.create')}
            </QuickFormFooter>
        </form>
    );
}
