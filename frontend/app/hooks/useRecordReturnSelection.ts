'use client';

import { useEffect, useMemo, useRef, type Dispatch, type SetStateAction } from 'react';

import type { SelectionId } from '@/app/components/records/types';
import { useActions } from '@/app/hooks/useActions';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import {
    consumeRecordReturnSelection,
    type RecordCollection,
    type RecordReturnSelectionSnapshot,
} from '@/app/lib/recordReturnPath';

/**
 * Restores a one-shot record selection after browser Back and returns the current scoped snapshot
 * for detail navigation.
 */
export function useRecordReturnSelection(
    collection: RecordCollection,
    selectedIds: Set<SelectionId>,
    setSelectedIds: Dispatch<SetStateAction<Set<SelectionId>>>,
    availableRecords: readonly { id: number }[],
    ready: boolean,
): RecordReturnSelectionSnapshot | undefined {
    const { context } = useActions();
    const { activeWorkspaceId } = useWorkspace();
    const userId = context.user?.id ?? null;
    const restoredScopeRef = useRef<string | null>(null);
    const availableIds = useMemo(
        () => new Set(availableRecords.map((record) => record.id)),
        [availableRecords],
    );

    useEffect(() => {
        if (!ready || userId === null || activeWorkspaceId === null) return;
        const scope = `${collection}:${userId}:${activeWorkspaceId}`;
        if (restoredScopeRef.current === scope) return;
        restoredScopeRef.current = scope;
        const restored = consumeRecordReturnSelection(collection, userId, activeWorkspaceId);
        if (!restored) return;
        setSelectedIds(new Set(restored.ids.filter((id) => availableIds.has(id))));
        let secondFrame: number | null = null;
        const firstFrame = window.requestAnimationFrame(() => {
            secondFrame = window.requestAnimationFrame(() => {
                const scrollRoot = document.querySelector<HTMLElement>('[data-app-main]');
                if (scrollRoot) scrollRoot.scrollTop = restored.scrollTop;
            });
        });
        return () => {
            window.cancelAnimationFrame(firstFrame);
            if (secondFrame !== null) window.cancelAnimationFrame(secondFrame);
        };
    }, [activeWorkspaceId, availableIds, collection, ready, setSelectedIds, userId]);

    return useMemo(() => {
        if (userId === null || activeWorkspaceId === null) return undefined;
        return {
            userId,
            workspaceId: activeWorkspaceId,
            ids: Array.from(selectedIds, Number),
        };
    }, [activeWorkspaceId, selectedIds, userId]);
}
