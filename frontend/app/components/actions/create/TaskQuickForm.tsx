'use client';

import { useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { Bars3BottomLeftIcon, CalendarIcon } from '@heroicons/react/24/outline';
import { Loader2Icon } from 'lucide-react';

import { Label } from '@/components/ui/label';
import { fieldInputClass, fieldLeadIconClass } from '@/components/ui/dialog-status-cover';
import { cn } from '@/lib/utils';
import { createTask, isFieldError } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { useFieldErrors } from '@/app/hooks/useFieldErrors';
import type { CreateDefaults } from '@/app/lib/actions/types';

import QuickFormContextChip from './QuickFormContextChip';
import QuickFormFooter from './QuickFormFooter';

/**
 * Minimal in-panel task capture. Calls the same {@link createTask} implementation the full dialog uses,
 * assigning to the current user by default and carrying any person/deal context from the launcher. The
 * "More details" hatch escalates to the full task dialog without losing the description.
 */
export default function TaskQuickForm({
    defaults,
    currentUserId,
    onCreated,
    onMoreDetails,
}: {
    defaults?: CreateDefaults;
    currentUserId: number;
    onCreated: () => void;
    onMoreDetails: (draft: { description: string }) => void;
}) {
    const router = useRouter();
    const t = useTranslations('Actions');
    const submittingRef = useRef(false);

    const [description, setDescription] = useState('');
    const [dueDate, setDueDate] = useState('');
    const [submitting, setSubmitting] = useState(false);
    const { fieldErrors, reset: resetFieldErrors, clearError, captureFieldErrors } = useFieldErrors();

    const handleSubmit = async (e: React.SubmitEvent<HTMLFormElement>) => {
        e.preventDefault();
        if (submittingRef.current) return;
        resetFieldErrors();
        submittingRef.current = true;
        setSubmitting(true);
        try {
            await createTask({
                description: description.trim(),
                dueDate: dueDate || undefined,
                assignedToId: currentUserId,
                personId: defaults?.personId,
                dealId: defaults?.dealId,
            });
            toastSuccess(t('feedback.taskCreated'));
            router.refresh();
            onCreated();
        } catch (err) {
            if (captureFieldErrors(err)) {
                if (isFieldError(err)) {
                    const firstKey = Object.keys(err.fieldErrors)[0];
                    if (firstKey) requestAnimationFrame(() => document.getElementById(`quick-task-${firstKey}`)?.focus());
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
                <Label htmlFor="quick-task-description">{t('quickCreate.task.descriptionLabel')}</Label>
                <div className="group relative">
                    <Bars3BottomLeftIcon className="pointer-events-none absolute left-3 top-2.5 size-4 text-muted-foreground transition-colors group-focus-within:text-brand" />
                    <textarea
                        id="quick-task-description"
                        value={description}
                        onChange={(e) => {
                            setDescription(e.target.value);
                            clearError('description');
                        }}
                        placeholder={t('quickCreate.task.descriptionPlaceholder')}
                        rows={2}
                        autoFocus
                        aria-invalid={Boolean(fieldErrors.description)}
                        className={cn(fieldInputClass, 'min-h-16 resize-none py-2 pl-9 pr-3')}
                    />
                </div>
                {fieldErrors.description && <p className="text-sm text-destructive">{fieldErrors.description}</p>}
            </div>

            <div className="grid gap-1.5">
                <Label htmlFor="quick-task-due">{t('quickCreate.task.dueDateLabel')}</Label>
                <div className="group relative">
                    <CalendarIcon className={fieldLeadIconClass} />
                    <input
                        id="quick-task-due"
                        type="date"
                        value={dueDate}
                        onChange={(e) => setDueDate(e.target.value)}
                        className={cn(fieldInputClass, 'pl-9 pr-3')}
                    />
                </div>
            </div>

            <QuickFormFooter
                onMoreDetails={() => onMoreDetails({ description })}
                submitDisabled={submitting || !description.trim()}
            >
                {submitting ? <Loader2Icon className="size-4 animate-spin" /> : t('quickCreate.create')}
            </QuickFormFooter>
        </form>
    );
}
