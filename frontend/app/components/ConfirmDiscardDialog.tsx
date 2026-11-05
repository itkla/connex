'use client';

import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { useTranslations } from 'next-intl';
import { useRef } from 'react';

type Props = {
    open: boolean;
    /** Keeps the form open so the user can continue editing. */
    onKeepEditing: () => void;
    /** Discards the unsaved edits and closes the form. */
    onDiscard: () => void;
};

/**
 * Focused in-app confirmation shown when a user tries to dismiss a form with unsaved edits, driven by
 * {@link useUnsavedChangesGuard}. Default focus lands on "Keep editing" so an accidental Enter never
 * discards; "Discard changes" is destructive.
 */
export default function ConfirmDiscardDialog({ open, onKeepEditing, onDiscard }: Props) {
    const t = useTranslations('ConfirmDiscard');
    const keepEditingRef = useRef<HTMLButtonElement>(null);
    const returnFocusRef = useRef<HTMLElement | null>(null);
    return (
        <Dialog open={open} onOpenChange={(next) => { if (!next) onKeepEditing(); }}>
            <DialogContent
                className="sm:max-w-sm"
                onEscapeKeyDown={(event) => {
                    event.preventDefault();
                    event.stopPropagation();
                    onKeepEditing();
                }}
                onOpenAutoFocus={(event) => {
                    event.preventDefault();
                    returnFocusRef.current = document.activeElement instanceof HTMLElement
                        ? document.activeElement : null;
                    keepEditingRef.current?.focus();
                }}
                onCloseAutoFocus={(event) => {
                    if (!returnFocusRef.current?.isConnected) return;
                    event.preventDefault();
                    returnFocusRef.current.focus();
                }}
            >
                <DialogHeader>
                    <DialogTitle>{t('title')}</DialogTitle>
                    <DialogDescription>{t('description')}</DialogDescription>
                </DialogHeader>
                <DialogFooter>
                    <Button ref={keepEditingRef} variant="outline" onClick={onKeepEditing}>
                        {t('keepEditing')}
                    </Button>
                    <Button variant="destructive" className="bg-destructive text-destructive-foreground hover:bg-destructive/90" onClick={onDiscard}>
                        {t('discard')}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}
