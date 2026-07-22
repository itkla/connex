"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";

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

type RecentRecordsContextValue = {
    /** The current user's most-recently-viewed records for the active workspace, newest first. */
    recents: RecentRecord[];
    /** Records a record view, moving it to the front of the MRU list; a no-op for an empty label. */
    record: (input: RecentRecordInput) => void;
};

const RecentRecordsContext = createContext<RecentRecordsContextValue | null>(null);

/**
 * Keeps the current user's most-recently-viewed records for the active workspace so the sidebar and
 * command palette can surface them as shortcuts. Pure local storage — no network — scoped per user +
 * workspace so views never leak across accounts on a shared browser. Read after mount to avoid a
 * hydration mismatch, and reset when the scope key changes on a workspace or user switch. Mount it
 * inside {@code ActionProvider} so the palette bridge beneath it can register navigation actions from
 * the same data.
 */
export function RecentRecordsProvider({ children }: { children: ReactNode }) {
    const { context } = useActions();
    const { activeWorkspaceId } = useWorkspace();
    const key = recentRecordsStorageKey(context.user?.id ?? null, activeWorkspaceId);
    const [recents, setRecents] = useState<RecentRecord[]>([]);
    const recentsRef = useRef<RecentRecord[]>(recents);

    useEffect(() => {
        let raw: string | null = null;
        try {
            raw = window.localStorage.getItem(key);
        } catch {
            raw = null;
        }
        const next = parseRecents(raw);
        recentsRef.current = next;
        // eslint-disable-next-line react-hooks/set-state-in-effect
        setRecents(next);
    }, [key]);

    const record = useCallback(
        (input: RecentRecordInput) => {
            const label = input.label.trim();
            if (label.length === 0) return;
            const next = upsertRecent(recentsRef.current, { t: input.type, id: input.id, label, ts: Date.now() });
            recentsRef.current = next;
            setRecents(next);
            try {
                window.localStorage.setItem(key, serializeRecents(next));
            } catch {}
        },
        [key],
    );

    const value = useMemo<RecentRecordsContextValue>(() => ({ recents, record }), [recents, record]);
    return <RecentRecordsContext.Provider value={value}>{children}</RecentRecordsContext.Provider>;
}

/** The current user's recent records for the active workspace. Throws if used outside {@link RecentRecordsProvider}. */
export function useRecentRecords(): RecentRecordsContextValue {
    const value = useContext(RecentRecordsContext);
    if (!value) throw new Error("useRecentRecords must be used within RecentRecordsProvider");
    return value;
}
