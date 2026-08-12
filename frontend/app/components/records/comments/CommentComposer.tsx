'use client';

import { useEffect, useRef, useState, type ClipboardEvent, type DragEvent } from 'react';
import { useTranslations } from 'next-intl';
import { ImagePlus, LoaderCircle } from 'lucide-react';

import { cn } from '@/lib/utils';
import { DRAFT_VERSIONS, readDraft, type DraftKeyParts } from '@/app/lib/formDrafts';
import { useFormDraft } from '@/app/hooks/useFormDraft';
import { isManagedImageFile, MANAGED_IMAGE_ACCEPT } from '@/app/lib/managed-image';
import { toastError } from '@/app/lib/toast';
import {
    appendCommentImage,
    commentImageMarkdown,
    isCommentDraft,
    type CommentDraft,
} from '@/app/components/records/comments/commentText';
import MentionEditor, {
    type MentionEditorHandle,
} from '@/app/components/activity/notes/MentionEditor';
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
    /**
     * When set, the composer accepts image files through paste, drop, and an
     * attach button: each file is handed to this callback, which uploads it
     * and resolves the embeddable app-relative URL, or null when the upload
     * failed (the callback owns that failure's toast). Successful uploads
     * append a Markdown image embed to the value; submission stays gated
     * while uploads are in flight.
     */
    onAttachImage?: (file: File) => Promise<string | null>;
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
    onAttachImage,
}: Props) {
    const t = useTranslations('Comments');
    const [focused, setFocused] = useState(autoFocus ?? false);
    const [uploadingCount, setUploadingCount] = useState(0);
    const fileInputRef = useRef<HTMLInputElement>(null);
    const editorHandle = useRef<MentionEditorHandle>(null);
    const uploading = uploadingCount > 0;
    const engaged = focused || value.length > 0 || submitting || uploading;
    const submitReady = canSubmit && !submitting && !disabled && !uploading;

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

    const attachFiles = async (files: Iterable<File>) => {
        if (!onAttachImage) return;
        for (const file of files) {
            if (!(await isManagedImageFile(file))) {
                toastError(t('imageUnsupportedType'));
                continue;
            }
            setUploadingCount((count) => count + 1);
            try {
                const url = await onAttachImage(file);
                if (url) {
                    const markdown = commentImageMarkdown(file.name, url);
                    const editor = editorHandle.current;
                    if (editor) {
                        editor.appendParagraph(markdown);
                    } else {
                        const next = appendCommentImage(valueRef.current, markdown);
                        valueRef.current = next;
                        onChangeRef.current(next);
                    }
                }
            } finally {
                setUploadingCount((count) => count - 1);
            }
        }
    };

    const onPaste = (event: ClipboardEvent<HTMLDivElement>) => {
        if (!onAttachImage || submitting || disabled) return;
        const files = Array.from(event.clipboardData?.files ?? []);
        if (files.length === 0) return;
        event.preventDefault();
        void attachFiles(files);
    };

    const onDrop = (event: DragEvent<HTMLDivElement>) => {
        if (!onAttachImage || submitting || disabled) return;
        const files = Array.from(event.dataTransfer?.files ?? []);
        if (files.length === 0) return;
        event.preventDefault();
        void attachFiles(files);
    };

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
            onPaste={onPaste}
            onDrop={onDrop}
            onDragOver={onAttachImage ? (event) => event.preventDefault() : undefined}
        >
            <MentionEditor
                value={value}
                onChange={onChange}
                placeholder={placeholder}
                ariaLabel={submitLabel}
                autoFocus={autoFocus}
                onSubmit={submitReady ? onSubmit : undefined}
                className="min-h-9 px-3.5 py-2 text-sm outline-none"
                handleRef={editorHandle}
            />
            <div
                className="grid transition-[grid-template-rows] duration-200 ease-out motion-reduce:transition-none"
                style={{ gridTemplateRows: engaged ? '1fr' : '0fr' }}
            >
                <div className="overflow-hidden">
                    <div className="flex items-center justify-between gap-3 px-3.5 pb-2 pt-0.5">
                        <div className="flex min-w-0 items-center gap-1.5">
                            {onAttachImage && (
                                <>
                                    <input
                                        ref={fileInputRef}
                                        type="file"
                                        accept={MANAGED_IMAGE_ACCEPT}
                                        multiple
                                        className="hidden"
                                        onChange={(event) => {
                                            const files = Array.from(event.target.files ?? []);
                                            event.target.value = '';
                                            if (files.length > 0) void attachFiles(files);
                                        }}
                                    />
                                    <button
                                        type="button"
                                        className={cn(
                                            'flex size-7 shrink-0 cursor-pointer items-center justify-center rounded-lg text-muted-foreground transition-colors',
                                            'hover:bg-accent/60 hover:text-foreground active:scale-[0.97]',
                                            'focus-visible:outline-2 focus-visible:outline-ring',
                                            'disabled:pointer-events-none disabled:opacity-60',
                                        )}
                                        aria-label={t('attachImage')}
                                        title={t('attachImage')}
                                        disabled={submitting || disabled}
                                        onClick={() => fileInputRef.current?.click()}
                                    >
                                        {uploading ? (
                                            <LoaderCircle className="size-4 animate-spin" aria-hidden="true" />
                                        ) : (
                                            <ImagePlus className="size-4" aria-hidden="true" />
                                        )}
                                    </button>
                                </>
                            )}
                            <p aria-live="polite" className="truncate text-xs text-muted-foreground/80">
                                {uploading ? t('imageUploading') : t('composerHint')}
                            </p>
                        </div>
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
                                disabled={!submitReady}
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
