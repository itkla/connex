'use client';

import { useCallback, useEffect, useMemo, useRef } from 'react';

import { clearDraft, draftKey, writeDraft, type DraftKeyParts } from '@/app/lib/formDrafts';

const DRAFT_DEBOUNCE_MS = 400;

type UseFormDraftOptions = {
    keyParts: DraftKeyParts;
    version: number;
};

/** Persists a composer draft, and clears it, both scoped to one stable storage key. */
export type FormDraftControls<T> = {
    /** Schedules a debounced write of the current snapshot; call while the form holds unsaved edits. */
    persist: (snapshot: T) => void;
    /** Removes the draft and cancels any pending write; call on successful submit or explicit discard. */
    clear: () => void;
};

/**
 * Draft-persistence controls for a create composer. Writes are debounced and flushed on `pagehide` so the
 * last keystrokes before an accidental reload survive; a pending write is cancelled on unmount so a discarded
 * draft is never resurrected by a late timer. The hook itself never decides *when* to persist or clear — the
 * composer persists while dirty and clears on success, and its wrapper clears on an explicit discard — so a
 * pristine open/close leaves any existing draft untouched for the resume banner to surface.
 */
export function useFormDraft<T>({ keyParts, version }: UseFormDraftOptions): FormDraftControls<T> {
    const { userId, workspaceId, formType, scope } = keyParts;
    const key = useMemo(
        () => draftKey({ userId, workspaceId, formType, scope }),
        [userId, workspaceId, formType, scope],
    );

    const timerRef = useRef<number | null>(null);
    const pendingRef = useRef<T | null>(null);

    const cancel = useCallback(() => {
        if (timerRef.current !== null) {
            window.clearTimeout(timerRef.current);
            timerRef.current = null;
        }
        pendingRef.current = null;
    }, []);

    const flush = useCallback(() => {
        if (timerRef.current !== null) {
            window.clearTimeout(timerRef.current);
            timerRef.current = null;
        }
        if (pendingRef.current !== null) {
            const data = pendingRef.current;
            pendingRef.current = null;
            writeDraft(key, { version, scope, formType, data });
        }
    }, [key, version, scope, formType]);

    const persist = useCallback(
        (snapshot: T) => {
            pendingRef.current = snapshot;
            if (timerRef.current !== null) window.clearTimeout(timerRef.current);
            timerRef.current = window.setTimeout(() => {
                timerRef.current = null;
                if (pendingRef.current === null) return;
                const data = pendingRef.current;
                pendingRef.current = null;
                writeDraft(key, { version, scope, formType, data });
            }, DRAFT_DEBOUNCE_MS);
        },
        [key, version, scope, formType],
    );

    const clear = useCallback(() => {
        cancel();
        clearDraft(key);
    }, [cancel, key]);

    useEffect(() => {
        const onPageHide = () => flush();
        window.addEventListener('pagehide', onPageHide);
        return () => window.removeEventListener('pagehide', onPageHide);
    }, [flush]);

    useEffect(() => () => cancel(), [cancel]);

    return { persist, clear };
}
