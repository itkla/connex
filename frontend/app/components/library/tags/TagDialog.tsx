'use client';

import { useState } from 'react';
import { useTranslations } from 'next-intl';
import { motion, useReducedMotion } from 'motion/react';
import { Loader2Icon } from 'lucide-react';
import { CheckIcon } from '@heroicons/react/24/solid';

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

const inputClass =
    'w-full rounded-lg bg-neutral-100 px-3 py-2 text-sm text-black placeholder-neutral-500 outline-none ring-1 ring-black/5 transition focus:ring-2 focus:ring-brand';
const MAX_NAME = 40;
const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];

export default function TagDialog({ open, onOpenChange, mode, tag, onSaved }: Props) {
    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent>
                <TagForm
                    key={`${mode}-${tag?.id ?? 'new'}`}
                    mode={mode}
                    tag={tag ?? null}
                    onSaved={onSaved}
                    onClose={() => onOpenChange(false)}
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
}: {
    mode: 'create' | 'edit';
    tag: Tag | null;
    onSaved: (tag: Tag) => void;
    onClose: () => void;
}) {
    const t = useTranslations('ActivityLibraryTagDialog');
    const reduce = useReducedMotion() ?? false;

    const initialColor =
        mode === 'edit' && tag?.color ? normalizeHex(tag.color) ?? DEFAULT_TAG_COLOR : DEFAULT_TAG_COLOR;

    const [name, setName] = useState(mode === 'edit' ? tag?.name ?? '' : '');
    const [color, setColor] = useState(initialColor);
    const [hexInput, setHexInput] = useState(initialColor);
    const [submitting, setSubmitting] = useState(false);

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
    const canSubmit = trimmedName.length > 0 && hexValid && !submitting;
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
            onSaved(saved);
            onClose();
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
            <DialogHeader>
                <DialogTitle>{mode === 'create' ? t('titleCreate') : t('titleEdit')}</DialogTitle>
                <DialogDescription>{t('description')}</DialogDescription>
            </DialogHeader>

            <div className="flex min-h-20 items-center justify-center rounded-xl bg-neutral-50 px-4 py-5 ring-1 ring-black/5">
                <motion.span
                    className="inline-flex max-w-full items-center rounded-4xl px-3 py-1 text-sm font-medium"
                    animate={{ backgroundColor: color, color: previewInk }}
                    transition={{ duration: reduce ? 0 : 0.2, ease: EASE_OUT }}
                >
                    <span className="truncate">{trimmedName || t('previewPlaceholder')}</span>
                </motion.span>
            </div>

            <form onSubmit={handleSubmit} className="grid gap-4">
                <div className="grid gap-1.5">
                    <Label htmlFor="tag-name">{t('nameLabel')}</Label>
                    <input
                        id="tag-name"
                        value={name}
                        onChange={(e) => setName(e.target.value)}
                        placeholder={t('namePlaceholder')}
                        className={inputClass}
                        maxLength={MAX_NAME}
                        autoFocus
                        required
                    />
                </div>

                <div className="grid gap-2">
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
                                    className={`flex aspect-square items-center justify-center rounded-lg ring-1 ring-inset ring-black/10 ${
                                        selected ? 'ring-2 ring-offset-2 ring-neutral-900' : ''
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
                            className="relative size-9 shrink-0 cursor-pointer overflow-hidden rounded-lg ring-1 ring-inset ring-black/10"
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
                            className={`${inputClass} font-mono uppercase ${
                                hexValid ? '' : 'ring-2 ring-destructive focus:ring-destructive'
                            }`}
                        />
                    </div>
                    {!hexValid && <p className="text-xs text-destructive">{t('hexInvalid')}</p>}
                </div>

                <DialogFooter>
                    <DialogClose asChild>
                        <Button type="button" variant="outline" disabled={submitting}>
                            {t('cancel')}
                        </Button>
                    </DialogClose>
                    <Button type="submit" disabled={!canSubmit} className="bg-brand text-white hover:bg-brand-dark">
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
        </>
    );
}