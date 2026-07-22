'use client';

import { useCallback, useEffect, useState } from 'react';

type GuardOptions = {
    /** Whether the form currently holds unsaved edits. */
    isDirty: boolean;
    /** Performs the real close (e.g. `() => onOpenChange(false)`). */
    onClose: () => void;
    /** When false the guard is inert and dismissals close immediately (e.g. while submitting). */
    enabled?: boolean;
};

type Guard = {
    /** Wrap a surface's `onOpenChange`; a dirty dismissal opens the confirm instead of closing. */
    onOpenChange: (open: boolean) => void;
    /** For dismiss paths that bypass `onOpenChange` (a Cancel/back button). */
    requestClose: () => void;
    /** Drives the confirm-discard dialog. */
    confirm: { open: boolean; onKeepEditing: () => void; onDiscard: () => void };
};

/**
 * Protects a form from accidental discard. While `isDirty && enabled`, any dismissal (the dialog X,
 * Escape, outside-click, or drawer swipe routed through {@link Guard.onOpenChange}, or a button routed
 * through {@link Guard.requestClose}) opens an in-app confirm instead of closing, and a `beforeunload`
 * listener catches refresh / tab-close / external navigation. Once the user confirms, {@link onClose}
 * runs. Keep `enabled` false during submit and the post-success beat so a programmatic close never
 * prompts.
 */
export function useUnsavedChangesGuard({ isDirty, onClose, enabled = true }: GuardOptions): Guard {
    const [confirmOpen, setConfirmOpen] = useState(false);
    const active = isDirty && enabled;

    useEffect(() => {
        if (!active) return;
        const onBeforeUnload = (event: BeforeUnloadEvent) => {
            event.preventDefault();
            event.returnValue = '';
        };
        window.addEventListener('beforeunload', onBeforeUnload);
        return () => window.removeEventListener('beforeunload', onBeforeUnload);
    }, [active]);

    const requestClose = useCallback(() => {
        if (active) {
            setConfirmOpen(true);
        } else {
            onClose();
        }
    }, [active, onClose]);

    const onOpenChange = useCallback(
        (open: boolean) => {
            if (open) return;
            requestClose();
        },
        [requestClose],
    );

    const onKeepEditing = useCallback(() => setConfirmOpen(false), []);
    const onDiscard = useCallback(() => {
        setConfirmOpen(false);
        onClose();
    }, [onClose]);

    return {
        onOpenChange,
        requestClose,
        confirm: { open: confirmOpen, onKeepEditing, onDiscard },
    };
}
