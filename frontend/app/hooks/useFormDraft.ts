'use client';

import { useCallback, useEffect, useMemo, useRef } from 'react';

import {
    clearDraft,
    draftKey,
    getDraftStoreGeneration,
    writeDraft,
    type DraftKeyParts,
} from '@/app/lib/formDrafts';

const DRAFT_DEBOUNCE_MS = 400;

type UseFormDraftOptions = {
    keyParts: DraftKeyParts;
    version: number;
};

type PendingDraft<T> = {
    generation: number;
    key: string;
    version: number;
    scope: string;
    formType: string;
    data: T;
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
 * last keystrokes before an accidental reload survive. Pending work also flushes on unmount so route and
 * workspace transitions preserve the origin-scoped snapshot; explicit clear cancels it before a successful
 * submit or confirmed discard can unmount the wrapper. The hook itself never decides *when* to persist or
 * clear, so a pristine open/close leaves any existing draft untouched for the resume banner to surface.
 */
export function useFormDraft<T>({ keyParts, version }: UseFormDraftOptions): FormDraftControls<T> {
    const { userId, workspaceId, formType, scope } = keyParts;
    const key = useMemo(
        () => draftKey({ userId, workspaceId, formType, scope }),
        [userId, workspaceId, formType, scope],
    );

    const timerRef = useRef<number | null>(null);
    const pendingRef = useRef<PendingDraft<T> | null>(null);

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
            const pending = pendingRef.current;
            pendingRef.current = null;
            if (pending.generation === getDraftStoreGeneration()) writeDraft(pending.key, pending);
        }
    }, []);

    const persist = useCallback(
        (snapshot: T) => {
            pendingRef.current = {
                generation: getDraftStoreGeneration(),
                key,
                version,
                scope,
                formType,
                data: snapshot,
            };
            if (timerRef.current !== null) window.clearTimeout(timerRef.current);
            timerRef.current = window.setTimeout(() => {
                timerRef.current = null;
                if (pendingRef.current === null) return;
                const pending = pendingRef.current;
                pendingRef.current = null;
                if (pending.generation === getDraftStoreGeneration()) writeDraft(pending.key, pending);
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

    useEffect(() => () => flush(), [flush]);

    return { persist, clear };
}
