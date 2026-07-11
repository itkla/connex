'use client';

import { useState } from 'react';
import { useTranslations } from 'next-intl';
import { motion, useReducedMotion } from 'motion/react';
import { Loader2Icon } from 'lucide-react';
import { CheckIcon } from '@heroicons/react/24/solid';
import { TagIcon } from '@heroicons/react/24/outline';

import {
    Dialog,
    DialogContent,
    DialogHeader,
    DialogTitle,
    DialogDescription,
    DialogFooter,
    DialogClose,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import {
    DialogStatusCover,
    resolveDialogStatus,
    fieldInputClass,
    fieldErrorClass,
    fieldLeadIconClass,
} from '@/components/ui/dialog-status-cover';

import { cn } from '@/lib/utils';
import { ApiError, createTag, updateTag } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type { Tag } from '@/app/lib/types';
import { DEFAULT_TAG_COLOR, TAG_PALETTE } from './colors';
import { normalizeHex, readableTextColor } from '@/app/lib/utils';

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    mode: 'create' | 'edit';
    tag?: Tag | null;
    onSaved: (tag: Tag) => void;
};

const MAX_NAME = 40;
const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];

export default function TagDialog({ open, onOpenChange, mode, tag, onSaved }: Props) {
    const [submitting, setSubmitting] = useState(false);

    const handleOpenChange = (next: boolean) => {
        if (!next && submitting) return;
        onOpenChange(next);
    };

    return (
        <Dialog open={open} onOpenChange={handleOpenChange}>
            <DialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                <TagForm
                    key={`${mode}-${tag?.id ?? 'new'}`}
                    mode={mode}
                    tag={tag ?? null}
                    onSaved={onSaved}
                    onClose={() => onOpenChange(false)}
                    submitting={submitting}
                    setSubmitting={setSubmitting}
                />
            </DialogContent>
        </Dialog>
    );
}

function TagForm({
    mode,
    tag,
    onSaved,
    onClose,
    submitting,
    setSubmitting,
}: {
    mode: 'create' | 'edit';
    tag: Tag | null;
    onSaved: (tag: Tag) => void;
    onClose: () => void;
    submitting: boolean;
    setSubmitting: React.Dispatch<React.SetStateAction<boolean>>;
}) {
    const t = useTranslations('ActivityLibraryTagDialog');
    const reduce = useReducedMotion() ?? false;

    const initialColor =
        mode === 'edit' && tag?.color ? normalizeHex(tag.color) ?? DEFAULT_TAG_COLOR : DEFAULT_TAG_COLOR;

    const [name, setName] = useState(mode === 'edit' ? tag?.name ?? '' : '');
    const [color, setColor] = useState(initialColor);
    const [hexInput, setHexInput] = useState(initialColor);
    const [succeeded, setSucceeded] = useState(false);
    const status = resolveDialogStatus({ isLoading: submitting, isSuccess: succeeded });

    const pickColor = (next: string) => {
        const normalized = normalizeHex(next) ?? DEFAULT_TAG_COLOR;
        setColor(normalized);
        setHexInput(normalized);
    };

    const handleHexChange = (value: string) => {
        setHexInput(value);
        const normalized = normalizeHex(value);
        if (normalized) setColor(normalized);
    };

    const hexValid = normalizeHex(hexInput) !== null;
    const trimmedName = name.trim();
    const canSubmit = trimmedName.length > 0 && hexValid && !submitting && !succeeded;
    const previewInk = readableTextColor(color);

    const handleSubmit = async (e: React.SubmitEvent<HTMLFormElement>) => {
        e.preventDefault();
        if (!trimmedName) {
            toastError(t('toastNameRequired'));
            return;
        }
        const finalColor = normalizeHex(hexInput) ?? color;
        setSubmitting(true);
        try {
            const saved =
                mode === 'create'
                    ? await createTag({ name: trimmedName, color: finalColor })
                    : await updateTag(tag!.id, { name: trimmedName, color: finalColor });
            toastSuccess(mode === 'create' ? t('toastCreated') : t('toastUpdated'));
            setSubmitting(false);
            setSucceeded(true);
            setTimeout(() => {
                onSaved(saved);
                onClose();
            }, 900);
        } catch (err) {
            const message =
                err instanceof ApiError
                    ? err.message
                    : err instanceof Error
                      ? err.message
                      : t('toastFailedSave');
            toastError(message);
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <>
            <DialogStatusCover status={status} />

            <div className="px-6 pb-6">
            <DialogHeader className="ncd-rise -mt-12 mb-5" style={{ animationDelay: '40ms' }}>
                <DialogTitle className="text-xl font-semibold tracking-tight">{mode === 'create' ? t('titleCreate') : t('titleEdit')}</DialogTitle>
                <DialogDescription>{t('description')}</DialogDescription>
            </DialogHeader>

            <div className="ncd-rise flex min-h-20 items-center justify-center rounded-xl bg-muted px-4 py-5 ring-1 ring-border" style={{ animationDelay: '90ms' }}>
                <motion.span
                    className="inline-flex max-w-full items-center rounded-4xl px-3 py-1 text-sm font-medium"
                    animate={{ backgroundColor: color, color: previewInk }}
                    transition={{ duration: reduce ? 0 : 0.2, ease: EASE_OUT }}
                >
                    <span className="truncate">{trimmedName || t('previewPlaceholder')}</span>
                </motion.span>
            </div>

            <form onSubmit={handleSubmit} className="mt-4 grid gap-4">
                <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '140ms' }}>
                    <Label htmlFor="tag-name">{t('nameLabel')}</Label>
                    <div className="group relative">
                        <TagIcon className={fieldLeadIconClass} />
                        <input
                            id="tag-name"
                            value={name}
                            onChange={(e) => setName(e.target.value)}
                            placeholder={t('namePlaceholder')}
                            className={cn(fieldInputClass, 'pl-9 pr-3')}
                            maxLength={MAX_NAME}
                            autoFocus
                            required
                        />
                    </div>
                </div>

                <div className="ncd-rise grid gap-2" style={{ animationDelay: '190ms' }}>
                    <Label>{t('colorLabel')}</Label>
                    <div role="radiogroup" aria-label={t('paletteAria')} className="grid grid-cols-8 gap-2">
                        {TAG_PALETTE.map((swatch) => {
                            const selected = color === swatch;
                            return (
                                <motion.button
                                    key={swatch}
                                    type="button"
                                    role="radio"
                                    aria-checked={selected}
                                    aria-label={swatch}
                                    title={swatch}
                                    onClick={() => pickColor(swatch)}
                                    whileHover={reduce || selected ? undefined : { scale: 1.1 }}
                                    whileTap={reduce ? undefined : { scale: 0.92 }}
                                    transition={{ duration: 0.15, ease: EASE_OUT }}
                                    style={{ backgroundColor: swatch }}
                                    className={`flex aspect-square items-center justify-center rounded-lg ring-1 ring-inset ring-border ${
                                        selected ? 'ring-2 ring-offset-2 ring-foreground' : ''
                                    }`}
                                >
                                    {selected && (
                                        <CheckIcon
                                            className="size-4"
                                            style={{ color: readableTextColor(swatch) }}
                                        />
                                    )}
                                </motion.button>
                            );
                        })}
                    </div>

                    <div className="mt-1 flex items-center gap-2">
                        <label
                            className="relative size-9 shrink-0 cursor-pointer overflow-hidden rounded-lg ring-1 ring-inset ring-border"
                            style={{ backgroundColor: hexValid ? color : '#ffffff' }}
                            title={t('customColorLabel')}
                        >
                            <input
                                type="color"
                                value={hexValid ? color : DEFAULT_TAG_COLOR}
                                onChange={(e) => pickColor(e.target.value)}
                                aria-label={t('customColorLabel')}
                                className="absolute inset-0 cursor-pointer opacity-0"
                            />
                        </label>
                        <input
                            value={hexInput}
                            onChange={(e) => handleHexChange(e.target.value)}
                            spellCheck={false}
                            aria-label={t('hexLabel')}
                            aria-invalid={!hexValid}
                            className={cn(fieldInputClass, 'px-3 font-mono uppercase tracking-wide', !hexValid && fieldErrorClass)}
                        />
                    </div>
                    {!hexValid && <p className="text-xs text-destructive">{t('hexInvalid')}</p>}
                </div>

                <DialogFooter className="ncd-rise mt-1" style={{ animationDelay: '240ms' }}>
                    <DialogClose asChild>
                        <Button type="button" variant="outline" disabled={submitting}>
                            {t('cancel')}
                        </Button>
                    </DialogClose>
                    <Button type="submit" variant="brand" disabled={!canSubmit} className="min-w-24 shadow-sm transition hover:shadow-md">
                        {submitting ? (
                            <Loader2Icon className="size-4 animate-spin" />
                        ) : mode === 'create' ? (
                            t('create')
                        ) : (
                            t('save')
                        )}
                    </Button>
                </DialogFooter>
            </form>
            </div>
        </>
    );
}
