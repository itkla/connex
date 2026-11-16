"use client";

import { createContext, useCallback, useContext, useLayoutEffect, useMemo, useState, useSyncExternalStore, type ReactNode } from "react";
import { useAccountStorageGeneration } from "@/app/hooks/useAccountStorageGeneration";

import { useActions } from "@/app/hooks/useActions";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import {
    parseRecents,
    recentRecordsStorageKey,
    serializeRecents,
    upsertRecent,
    type RecentRecord,
    type RecentRecordInput,
} from "@/app/lib/recentRecords";

type RecentRecordsValue = {
    /** The current user's most-recently-viewed records for the active workspace, newest first. */
    recents: RecentRecord[];
    /** Records a record view, moving it to the front of the MRU list; a no-op for an empty label. */
    record: (input: RecentRecordInput) => void;
};

const EMPTY_RECENTS: RecentRecord[] = [];

function createRecentRecordsStore() {
    let snapshot: { key: string; items: RecentRecord[] } | null = null;
    const listeners = new Set<() => void>();
    return {
        subscribe(listener: () => void) {
            listeners.add(listener);
            return () => { listeners.delete(listener); };
        },
        getSnapshot(key: string): RecentRecord[] {
            return snapshot?.key === key ? snapshot.items : EMPTY_RECENTS;
        },
        hasScope(key: string): boolean {
            return snapshot?.key === key;
        },
        clear() {
            if (!snapshot) return;
            snapshot = { key: snapshot.key, items: EMPTY_RECENTS };
            for (const listener of listeners) listener();
        },
        publish(key: string, items: RecentRecord[]) {
            snapshot = { key, items };
            for (const listener of listeners) listener();
        },
    };
}

type RecentRecordsContextValue = {
    subscribe: ReturnType<typeof createRecentRecordsStore>["subscribe"];
    getSnapshot: () => RecentRecord[];
    record: RecentRecordsValue["record"];
};

const RecentRecordsContext = createContext<RecentRecordsContextValue | null>(null);

function serverSnapshot(): RecentRecord[] {
    return EMPTY_RECENTS;
}

/**
 * Keeps the current user's most-recently-viewed records for the active workspace so the sidebar and
 * command palette can surface them as shortcuts. Pure local storage — no network — scoped per user +
 * workspace so views never leak across accounts on a shared browser. Each consumer hydrates from
 * the empty server snapshot before subscribing to the scoped cache, even if another consumer has
 * already recorded a view. Retained callbacks cannot write after the scope changes. Mount it
 * inside {@code ActionProvider} so the palette bridge beneath it can register navigation actions from
 * the same data.
 */
export function RecentRecordsProvider({ children }: { children: ReactNode }) {
    const { context } = useActions();
    const { activeWorkspaceId } = useWorkspace();
    const key = recentRecordsStorageKey(context.user?.id ?? null, activeWorkspaceId);
    const [store] = useState(createRecentRecordsStore);

    const clearRecents = useCallback(() => {
        store.clear();
    }, [store]);
    const canPersist = useAccountStorageGeneration(clearRecents);

    useLayoutEffect(() => {
        let raw: string | null = null;
        if (canPersist()) {
            try {
                raw = window.localStorage.getItem(key);
            } catch {
                raw = null;
            }
        }
        store.publish(key, parseRecents(raw));
    }, [canPersist, key, store]);

    const getSnapshot = useCallback(() => store.getSnapshot(key), [key, store]);

    const record = useCallback(
        (input: RecentRecordInput) => {
            if (!canPersist()) {
                clearRecents();
                return;
            }
            if (!store.hasScope(key)) return;
            const label = input.label.trim();
            if (label.length === 0) return;
            const next = upsertRecent(store.getSnapshot(key), { t: input.type, id: input.id, label, ts: Date.now() });
            store.publish(key, next);
            try {
                window.localStorage.setItem(key, serializeRecents(next));
            } catch {}
        },
        [canPersist, clearRecents, key, store],
    );

    const value = useMemo<RecentRecordsContextValue>(() => ({ subscribe: store.subscribe, getSnapshot, record }), [getSnapshot, record, store]);
    return <RecentRecordsContext.Provider value={value}>{children}</RecentRecordsContext.Provider>;
}

/** The current user's recent records for the active workspace. Throws if used outside {@link RecentRecordsProvider}. */
export function useRecentRecords(): RecentRecordsValue {
    const value = useContext(RecentRecordsContext);
    if (!value) throw new Error("useRecentRecords must be used within RecentRecordsProvider");
    const recents = useSyncExternalStore(value.subscribe, value.getSnapshot, serverSnapshot);
    return useMemo(() => ({ recents, record: value.record }), [recents, value.record]);
}
