'use client';

import { useTranslations } from 'next-intl';
import { LoaderCircle } from 'lucide-react';

import MentionEditor from '@/app/components/activity/notes/MentionEditor';
import { Button } from '@/components/ui/button';

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
};

/**
 * Shared MentionEditor composer for root comments and replies: editor plus a
 * right-aligned action row, with an optional cancel affordance for inline reply.
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
}: Props) {
    const t = useTranslations('Comments');

    return (
        <div className="flex flex-col gap-2">
            <MentionEditor
                value={value}
                onChange={onChange}
                placeholder={placeholder}
                ariaLabel={submitLabel}
                autoFocus={autoFocus}
                onSubmit={onSubmit}
                className="min-h-16 rounded-xl border border-border bg-background px-3 py-2 text-sm"
            />
            <div className="flex justify-end gap-2">
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
                    {submitting ? <LoaderCircle className="size-4 animate-spin" /> : submitLabel}
                </Button>
            </div>
        </div>
    );
}
