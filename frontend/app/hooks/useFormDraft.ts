'use client';

import { useCallback, useEffect, useRef, useState } from 'react';

import {
    advanceDraftKeyGeneration,
    clearDraftIfGeneration,
    draftKey,
    getDraftKeyGeneration,
    getDraftStoreGeneration,
    writeDraft,
    type DraftKeyParts,
} from '@/app/lib/formDrafts';

/** Delay used to coalesce draft writes while a composer is changing. */
export const DRAFT_DEBOUNCE_MS = 400;

type UseFormDraftOptions = {
    keyParts: DraftKeyParts;
    version: number;
    initialKeyGeneration?: number;
};

type PendingDraft<T> = {
    generation: number;
    keyGeneration: number;
    key: string;
    version: number;
    scope: string;
    formType: string;
    data: T;
};

/** Persists and clears a composer's draft while retaining its original user and workspace. */
export type FormDraftControls<T> = {
    /** Schedules a debounced write of the current snapshot; call while the form holds unsaved edits. */
    persist: (snapshot: T) => void;
    /** Removes this composer's owned draft and cancels its pending write without clearing a newer owner. */
    clear: () => void;
    /** After creation succeeds, clears the owned draft and uses the saved entity's scope for later edits. */
    rekeyScope: (scope: string) => void;
};

/**
 * Draft-persistence controls for a create composer. Writes are debounced and flushed on `pagehide` so the
 * last keystrokes before an accidental reload survive. Pending work also flushes on unmount so route and
 * workspace transitions preserve the origin-scoped snapshot; explicit clear cancels it before a successful
 * submit or confirmed discard can unmount the wrapper. The hook itself never decides *when* to persist or
 * clear, so a pristine open/close leaves any existing draft untouched for the resume banner to surface.
 * An editor that creates its entity in place can explicitly rekey its scope after the save succeeds;
 * changes to the caller's key parts alone never move a draft to another user or workspace.
 */
export function useFormDraft<T>({ keyParts, version, initialKeyGeneration }: UseFormDraftOptions): FormDraftControls<T> {
    const { userId, workspaceId, formType, scope } = keyParts;
    const [origin] = useState(() => ({
        userId,
        workspaceId,
        key: draftKey({ userId, workspaceId, formType, scope }),
        version,
        scope,
        formType,
    }));
    const originRef = useRef(origin);

    const timerRef = useRef<number | null>(null);
    const pendingRef = useRef<PendingDraft<T> | null>(null);
    const ownedKeyGenerationRef = useRef<number | null>(initialKeyGeneration ?? null);

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
            if (
                pending.generation === getDraftStoreGeneration() &&
                pending.keyGeneration === getDraftKeyGeneration(pending.key)
            ) {
                writeDraft(pending.key, pending);
            }
        }
    }, []);

    const persist = useCallback(
        (snapshot: T) => {
            const origin = originRef.current;
            const keyGeneration = advanceDraftKeyGeneration(origin.key);
            ownedKeyGenerationRef.current = keyGeneration;
            pendingRef.current = {
                generation: getDraftStoreGeneration(),
                keyGeneration,
                key: origin.key,
                version: origin.version,
                scope: origin.scope,
                formType: origin.formType,
                data: snapshot,
            };
            if (timerRef.current !== null) window.clearTimeout(timerRef.current);
            timerRef.current = window.setTimeout(() => {
                timerRef.current = null;
                if (pendingRef.current === null) return;
                const pending = pendingRef.current;
                pendingRef.current = null;
                if (
                    pending.generation === getDraftStoreGeneration() &&
                    pending.keyGeneration === getDraftKeyGeneration(pending.key)
                ) {
                    writeDraft(pending.key, pending);
                }
            }, DRAFT_DEBOUNCE_MS);
        },
        [],
    );

    const clear = useCallback(() => {
        cancel();
        const ownedKeyGeneration = ownedKeyGenerationRef.current;
        ownedKeyGenerationRef.current = null;
        if (ownedKeyGeneration !== null) clearDraftIfGeneration(originRef.current.key, ownedKeyGeneration);
    }, [cancel]);

    const rekeyScope = useCallback((scope: string) => {
        const origin = originRef.current;
        if (scope === origin.scope) return;
        clear();
        originRef.current = { ...origin, scope, key: draftKey({ ...origin, scope }) };
    }, [clear]);

    useEffect(() => {
        const onPageHide = () => flush();
        window.addEventListener('pagehide', onPageHide);
        return () => window.removeEventListener('pagehide', onPageHide);
    }, [flush]);

    useEffect(() => () => flush(), [flush]);

    return { persist, clear, rekeyScope };
}
