'use client';

import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { useTranslations } from 'next-intl';

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
    return (
        <Dialog open={open} onOpenChange={(next) => { if (!next) onKeepEditing(); }}>
            <DialogContent className="sm:max-w-sm">
                <DialogHeader>
                    <DialogTitle>{t('title')}</DialogTitle>
                    <DialogDescription>{t('description')}</DialogDescription>
                </DialogHeader>
                <DialogFooter>
                    <Button variant="destructive" className="bg-destructive text-destructive-foreground hover:bg-destructive/90" onClick={onDiscard}>
                        {t('discard')}
                    </Button>
                    <Button variant="outline" autoFocus onClick={onKeepEditing}>
                        {t('keepEditing')}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}
