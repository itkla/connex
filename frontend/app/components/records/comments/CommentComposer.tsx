'use client';

import { useEffect, useRef, useState } from 'react';
import { useTranslations } from 'next-intl';
import { LoaderCircle } from 'lucide-react';

import { cn } from '@/lib/utils';
import { DRAFT_VERSIONS, readDraft, type DraftKeyParts } from '@/app/lib/formDrafts';
import { useFormDraft } from '@/app/hooks/useFormDraft';
import {
    isCommentDraft,
    type CommentDraft,
} from '@/app/components/records/comments/commentText';
import MentionEditor from '@/app/components/activity/notes/MentionEditor';
import { Button } from '@/components/ui/button';

const POST_HYDRATION_RESTORE_DELAY_MS = 250;

const DISABLED_DRAFT_KEY: DraftKeyParts = {
    userId: null,
    workspaceId: null,
    formType: 'comment',
    scope: 'disabled',
};

type Props = {
    value: string;
    onChange: (value: string) => void;
    onSubmit: () => void;
    onCancel?: () => void;
    placeholder: string;
    submitLabel: string;
    submitting: boolean;
    disabled?: boolean;
    canSubmit: boolean;
    autoFocus?: boolean;
    /**
     * When set, the composer persists its value as a user+workspace-scoped
     * draft: debounced writes while typing, silent restore shortly after the
     * next mount with the same scope, and removal once the value returns to
     * empty through a post or discard. The scope is fixed at first mount,
     * matching {@link useFormDraft}'s origin semantics.
     */
    draftKeyParts?: DraftKeyParts;
};

/**
 * The comment input surface: a single quiet line that expands on focus to
 * reveal its hint and actions, mirroring the Ask Connex composer's focus-ring
 * finish. Enter submits, Shift+Enter breaks the line; the footer reveal is the
 * only motion and collapses to instant under reduced motion.
 */
export default function CommentComposer({
    value,
    onChange,
    onSubmit,
    onCancel,
    placeholder,
    submitLabel,
    submitting,
    disabled = false,
    canSubmit,
    autoFocus,
    draftKeyParts,
}: Props) {
    const t = useTranslations('Comments');
    const [focused, setFocused] = useState(autoFocus ?? false);
    const engaged = focused || value.length > 0 || submitting;

    const [draftOrigin] = useState<{ enabled: boolean; keyParts: DraftKeyParts }>(() => ({
        enabled: draftKeyParts != null,
        keyParts: draftKeyParts ?? DISABLED_DRAFT_KEY,
    }));
    const draft = useFormDraft<CommentDraft>({
        keyParts: draftOrigin.keyParts,
        version: DRAFT_VERSIONS.comment,
    });
    const restoreSettled = useRef(!draftOrigin.enabled);
    const valueRef = useRef(value);
    const onChangeRef = useRef(onChange);

    useEffect(() => {
        valueRef.current = value;
        onChangeRef.current = onChange;
    });

    useEffect(() => {
        if (!draftOrigin.enabled) return;
        const timer = window.setTimeout(() => {
            if (valueRef.current.length === 0) {
                const stored = readDraft(draftOrigin.keyParts, {
                    version: DRAFT_VERSIONS.comment,
                });
                if (stored && isCommentDraft(stored.data) && stored.data.content.length > 0) {
                    onChangeRef.current(stored.data.content);
                }
            }
            restoreSettled.current = true;
        }, POST_HYDRATION_RESTORE_DELAY_MS);
        return () => window.clearTimeout(timer);
    }, [draftOrigin]);

    useEffect(() => {
        if (!draftOrigin.enabled || !restoreSettled.current) return;
        if (value.length > 0) {
            draft.persist({ content: value });
        } else {
            draft.clear();
        }
    }, [draftOrigin, value, draft]);

    return (
        <div
            className={cn(
                'rounded-2xl border border-input bg-background transition-[border-color,box-shadow] duration-150',
                'focus-within:border-ring/50 focus-within:ring-2 focus-within:ring-ring/40',
            )}
            onFocusCapture={() => setFocused(true)}
            onBlurCapture={(event) => {
                if (!event.currentTarget.contains(event.relatedTarget as Node | null)) {
                    setFocused(false);
                }
            }}
        >
            <MentionEditor
                value={value}
                onChange={onChange}
                placeholder={placeholder}
                ariaLabel={submitLabel}
                autoFocus={autoFocus}
                onSubmit={canSubmit && !submitting && !disabled ? onSubmit : undefined}
                className="min-h-9 px-3.5 py-2 text-sm outline-none"
            />
            <div
                className="grid transition-[grid-template-rows] duration-200 ease-out motion-reduce:transition-none"
                style={{ gridTemplateRows: engaged ? '1fr' : '0fr' }}
            >
                <div className="overflow-hidden">
                    <div className="flex items-center justify-between gap-3 px-3.5 pb-2 pt-0.5">
                        <p className="truncate text-xs text-muted-foreground/80">
                            {t('composerHint')}
                        </p>
                        <div className="flex shrink-0 items-center gap-2">
                            {onCancel && (
                                <Button
                                    type="button"
                                    variant="ghost"
                                    size="sm"
                                    disabled={submitting}
                                    onClick={onCancel}
                                >
                                    {t('cancel')}
                                </Button>
                            )}
                            <Button
                                type="button"
                                variant="brand"
                                size="sm"
                                disabled={submitting || disabled || !canSubmit}
                                onClick={onSubmit}
                            >
                                {submitting ? (
                                    <LoaderCircle className="size-4 animate-spin" />
                                ) : (
                                    submitLabel
                                )}
                            </Button>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
}
