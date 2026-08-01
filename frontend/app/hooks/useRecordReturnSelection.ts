'use client';

import { useCallback, useEffect, useMemo, useRef, type Dispatch, type SetStateAction } from 'react';

import type { SelectionId } from '@/app/components/records/types';
import { useActions } from '@/app/hooks/useActions';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import {
    consumeRecordReturnSelection,
    type RecordCollection,
    type RecordReturnSelectionSnapshot,
} from '@/app/lib/recordReturnPath';

const EMPTY_AVAILABLE_IDS: ReadonlySet<number> = new Set();

/**
 * Shared body behind {@link useRecordReturnSelection} and {@link useRecordReturnScroll}: consumes the
 * one-shot return snapshot for the active owner exactly once per scope, hands any surviving ids to the
 * caller, and restores the scroll offset after two frames so the restored rows have been laid out.
 */
function useRecordReturnRestore(
    collection: RecordCollection,
    availableIds: ReadonlySet<number>,
    ready: boolean,
    applyIds: ((ids: readonly number[]) => void) | null,
): { userId: number | null; workspaceId: number | null } {
    const { context } = useActions();
    const { activeWorkspaceId } = useWorkspace();
    const userId = context.user?.id ?? null;
    const restoredScopeRef = useRef<string | null>(null);

    useEffect(() => {
        if (!ready || userId === null || activeWorkspaceId === null) return;
        const scope = `${collection}:${userId}:${activeWorkspaceId}`;
        if (restoredScopeRef.current === scope) return;
        restoredScopeRef.current = scope;
        const restored = consumeRecordReturnSelection(collection, userId, activeWorkspaceId);
        if (!restored) return;
        applyIds?.(restored.ids.filter((id) => availableIds.has(id)));
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
    }, [activeWorkspaceId, applyIds, availableIds, collection, ready, userId]);

    return { userId, workspaceId: activeWorkspaceId };
}

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
    const availableIds = useMemo(
        () => new Set(availableRecords.map((record) => record.id)),
        [availableRecords],
    );
    const applyIds = useCallback(
        (ids: readonly number[]) => setSelectedIds(new Set(ids)),
        [setSelectedIds],
    );
    const { userId, workspaceId } = useRecordReturnRestore(collection, availableIds, ready, applyIds);

    return useMemo(() => {
        if (userId === null || workspaceId === null) return undefined;
        return {
            userId,
            workspaceId,
            ids: Array.from(selectedIds, Number),
        };
    }, [workspaceId, selectedIds, userId]);
}

/**
 * Restores scroll position after browser Back for a list that has no multi-selection, and returns the
 * scoped snapshot its rows should navigate with so the offset is captured on the way out.
 */
export function useRecordReturnScroll(
    collection: RecordCollection,
    ready: boolean,
): RecordReturnSelectionSnapshot | undefined {
    const { userId, workspaceId } = useRecordReturnRestore(collection, EMPTY_AVAILABLE_IDS, ready, null);

    return useMemo(() => {
        if (userId === null || workspaceId === null) return undefined;
        return { userId, workspaceId, ids: [] };
    }, [userId, workspaceId]);
}
