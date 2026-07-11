'use client';

import { useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { Loader2Icon } from 'lucide-react';

import { Label } from '@/components/ui/label';
import { fieldInputClass } from '@/components/ui/dialog-status-cover';
import { cn } from '@/lib/utils';
import { createNote, isFieldError } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { useFieldErrors } from '@/app/hooks/useFieldErrors';
import type { CreateDefaults } from '@/app/lib/actions/types';

import QuickFormContextChip from './QuickFormContextChip';
import QuickFormFooter from './QuickFormFooter';

/**
 * Minimal in-panel note capture. Calls the same {@link createNote} implementation the full dialog uses,
 * authored by the current user and carrying any person/deal context. Notes default to their standard
 * visibility; the "More details" hatch escalates to the full dialog for visibility and linking.
 */
export default function NoteQuickForm({
    defaults,
    currentUserId,
    onCreated,
    onMoreDetails,
}: {
    defaults?: CreateDefaults;
    currentUserId: number;
    onCreated: () => void;
    onMoreDetails: (draft: { content: string }) => void;
}) {
    const router = useRouter();
    const t = useTranslations('Actions');
    const submittingRef = useRef(false);

    const [content, setContent] = useState('');
    const [submitting, setSubmitting] = useState(false);
    const { fieldErrors, reset: resetFieldErrors, clearError, captureFieldErrors } = useFieldErrors();

    const handleSubmit = async (e: React.SubmitEvent<HTMLFormElement>) => {
        e.preventDefault();
        if (submittingRef.current) return;
        resetFieldErrors();
        submittingRef.current = true;
        setSubmitting(true);
        try {
            await createNote({
                content: content.trim(),
                author: currentUserId,
                person: defaults?.personId ?? null,
                deal: defaults?.dealId ?? null,
            });
            toastSuccess(t('feedback.noteCreated'));
            router.refresh();
            onCreated();
        } catch (err) {
            if (captureFieldErrors(err)) {
                if (isFieldError(err)) {
                    const firstKey = Object.keys(err.fieldErrors)[0];
                    if (firstKey) requestAnimationFrame(() => document.getElementById(`quick-note-${firstKey}`)?.focus());
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
                <Label htmlFor="quick-note-content">{t('quickCreate.note.contentLabel')}</Label>
                <textarea
                    id="quick-note-content"
                    value={content}
                    onChange={(e) => {
                        setContent(e.target.value);
                        clearError('content');
                    }}
                    placeholder={t('quickCreate.note.contentPlaceholder')}
                    rows={3}
                    autoFocus
                    aria-invalid={Boolean(fieldErrors.content)}
                    className={cn(fieldInputClass, 'min-h-20 resize-none px-3 py-2')}
                />
                {fieldErrors.content && <p className="text-sm text-destructive">{fieldErrors.content}</p>}
            </div>

            <QuickFormFooter
                onMoreDetails={() => onMoreDetails({ content })}
                submitDisabled={submitting || !content.trim()}
            >
                {submitting ? <Loader2Icon className="size-4 animate-spin" /> : t('quickCreate.create')}
            </QuickFormFooter>
        </form>
    );
}
