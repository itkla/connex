'use client';

import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useTranslations } from 'next-intl';

import { fieldInputClass } from '@/components/ui/dialog-status-cover';
import { cn } from '@/lib/utils';
import { ApiError } from '@/app/lib/api';
import { toastError } from '@/app/lib/toast';

/**
 * A single built-in text field, displayed and editable in place inside a record-table cell. Double-click
 * (or keyboard-activate) opens an input; Enter/blur commits, Escape cancels. The commit is optimistic —
 * the new value shows immediately and is held locally (no list refetch, so scroll/selection are kept) —
 * and reverts with a toast if the save rejects. Clicks never bubble to the surrounding row.
 */
export default function EditableCell({
    value,
    display,
    onCommit,
    inputType = 'text',
    validate,
    ariaLabel,
}: {
    value: string | undefined | null;
    display?: ReactNode;
    onCommit: (next: string) => Promise<void>;
    inputType?: 'text' | 'url' | 'tel';
    validate?: (next: string) => string | null;
    ariaLabel: string;
}) {
    const t = useTranslations('RecordInlineEdit');
    const [editing, setEditing] = useState(false);
    const [saving, setSaving] = useState(false);

    const current = value ?? '';

    const commit = async (raw: string) => {
        setEditing(false);
        const next = raw.trim();
        if (next === current.trim()) return;
        const message = validate?.(next);
        if (message) {
            toastError(message);
            return;
        }
        setSaving(true);
        try {
            await onCommit(next);
        } catch (err) {
            toastError(err instanceof ApiError ? err.message : t('saveFailed'));
        } finally {
            setSaving(false);
        }
    };

    if (editing) {
        return (
            <span onClick={(event) => event.stopPropagation()} className="block">
                <CellInput value={current} inputType={inputType} onCommit={commit} onCancel={() => setEditing(false)} />
            </span>
        );
    }

    return (
        <button
            type="button"
            disabled={saving}
            aria-label={ariaLabel}
            title={t('doubleClickToEdit')}
            onClick={(event) => {
                event.stopPropagation();
                if (event.detail === 0) setEditing(true);
            }}
            onDoubleClick={() => setEditing(true)}
            className={cn(
                '-mx-1.5 block w-[calc(100%+0.75rem)] cursor-text truncate rounded-md px-1.5 py-0.5 text-left transition-colors hover:bg-accent/50',
                saving && 'opacity-60',
            )}
        >
            {display !== undefined ? (
                display
            ) : current === '' ? (
                <span className="text-muted-foreground/50">—</span>
            ) : (
                <span className="text-foreground">{current}</span>
            )}
        </button>
    );
}

function CellInput({
    value,
    inputType,
    onCommit,
    onCancel,
}: {
    value: string;
    inputType: 'text' | 'url' | 'tel';
    onCommit: (next: string) => void;
    onCancel: () => void;
}) {
    const [draft, setDraft] = useState(value);
    const inputRef = useRef<HTMLInputElement>(null);
    const doneRef = useRef(false);

    useEffect(() => {
        const input = inputRef.current;
        input?.focus();
        input?.select();
    }, []);

    const finish = (action: () => void) => {
        if (doneRef.current) return;
        doneRef.current = true;
        action();
    };

    return (
        <input
            ref={inputRef}
            type={inputType}
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            onBlur={() => finish(() => onCommit(draft))}
            onClick={(event) => event.stopPropagation()}
            onKeyDown={(event) => {
                if (event.key === 'Enter' && !event.nativeEvent.isComposing) {
                    event.preventDefault();
                    finish(() => onCommit(draft));
                } else if (event.key === 'Escape') {
                    event.preventDefault();
                    finish(onCancel);
                }
            }}
            maxLength={inputType === 'url' ? 2048 : 500}
            className={cn(fieldInputClass, 'h-8 px-2 text-sm')}
        />
    );
}
