'use client';

import {
    useCallback,
    useEffect,
    useRef,
    useSyncExternalStore,
} from 'react';

import {
    resolveViewPreference,
    viewPreferenceStorageKey,
} from './viewPreference';

const VIEW_PREFERENCE_EVENT = 'connex:view-preference';

/**
 * Reads a user- and workspace-scoped desktop view preference without hydration drift. Only the
 * returned setter persists a value, so viewport-forced presentation changes cannot overwrite the
 * user's desktop choice.
 */
export function useScopedViewPreference<T extends string>({
    storageKey,
    userId,
    workspaceId,
    initialValue,
    fallback,
    isValue,
}: {
    storageKey: string;
    userId: number | null;
    workspaceId: number | null;
    initialValue: T | null;
    fallback: T;
    isValue: (value: unknown) => value is T;
}): [T, (next: T) => void] {
    const scopedStorageKey = viewPreferenceStorageKey(storageKey, userId, workspaceId);
    const volatileValue = useRef<{ key: string; value: T } | null>(null);

    const readValue = useCallback(() => {
        const volatile = volatileValue.current?.key === scopedStorageKey
            ? volatileValue.current.value
            : null;
        if (volatile !== null) return volatile;
        try {
            const stored = window.localStorage.getItem(scopedStorageKey);
            return resolveViewPreference(initialValue, stored, fallback, isValue);
        } catch {
            return resolveViewPreference(initialValue, null, fallback, isValue);
        }
    }, [fallback, initialValue, isValue, scopedStorageKey]);

    const subscribe = useCallback((onStoreChange: () => void) => {
        const onStorage = (event: StorageEvent) => {
            if (event.key !== null && event.key !== scopedStorageKey) return;
            volatileValue.current = null;
            onStoreChange();
        };
        const onPreference = (event: Event) => {
            if (!(event instanceof CustomEvent) || event.detail !== scopedStorageKey) return;
            onStoreChange();
        };
        window.addEventListener('storage', onStorage);
        window.addEventListener(VIEW_PREFERENCE_EVENT, onPreference);
        return () => {
            window.removeEventListener('storage', onStorage);
            window.removeEventListener(VIEW_PREFERENCE_EVENT, onPreference);
        };
    }, [scopedStorageKey]);

    useEffect(() => {
        if (
            volatileValue.current?.key === scopedStorageKey
            && volatileValue.current.value === initialValue
        ) {
            volatileValue.current = null;
        }
    }, [initialValue, scopedStorageKey]);

    const value = useSyncExternalStore(
        subscribe,
        readValue,
        () => resolveViewPreference(initialValue, null, fallback, isValue),
    );

    const setValue = useCallback((next: T) => {
        volatileValue.current = { key: scopedStorageKey, value: next };
        try {
            window.localStorage.setItem(scopedStorageKey, next);
        } catch {}
        window.dispatchEvent(new CustomEvent(VIEW_PREFERENCE_EVENT, { detail: scopedStorageKey }));
    }, [scopedStorageKey]);

    return [value, setValue];
}
