'use client';

import { useCallback, useMemo, useSyncExternalStore } from 'react';
import { useAccountStorageGeneration } from "@/app/hooks/useAccountStorageGeneration";

import {
    parseCollapsedSidebarSections,
    serializeCollapsedSidebarSections,
    sidebarSectionStorageKey,
    type SidebarSectionId,
} from '@/app/lib/sidebarSections';

const SECTION_CHANGE_EVENT_PREFIX = 'connex:sidebar-sections-change:';
const volatileStates = new Map<string, string>();

function serverSnapshot(): null {
    return null;
}

function readStoredState(key: string): string | null {
    const volatileState = volatileStates.get(key);
    if (volatileState !== undefined) return volatileState;
    try {
        return window.localStorage.getItem(key);
    } catch {
        return null;
    }
}

/** Persists sidebar disclosure state per user and workspace while synchronizing browser tabs. */
export function useSidebarSections(
    userId: number | null | undefined,
    workspaceId: number | null | undefined,
): {
    isCollapsed: (sectionId: SidebarSectionId) => boolean;
    setCollapsed: (sectionId: SidebarSectionId, collapsed: boolean) => void;
} {
    const key = sidebarSectionStorageKey(userId, workspaceId);
    const eventName = `${SECTION_CHANGE_EVENT_PREFIX}${key}`;

    const canPersist = useAccountStorageGeneration(() => {
        volatileStates.delete(key);
        window.dispatchEvent(new Event(eventName));
    });

    const subscribe = useCallback(
        (onStoreChange: () => void) => {
            const onStorage = (event: StorageEvent) => {
                if (event.key === null || event.key === key) {
                    volatileStates.delete(key);
                    onStoreChange();
                }
            };
            window.addEventListener('storage', onStorage);
            window.addEventListener(eventName, onStoreChange);
            return () => {
                window.removeEventListener('storage', onStorage);
                window.removeEventListener(eventName, onStoreChange);
            };
        },
        [eventName, key],
    );
    const getSnapshot = useCallback(() => canPersist() ? readStoredState(key) : null, [canPersist, key]);
    const raw = useSyncExternalStore(subscribe, getSnapshot, serverSnapshot);
    const collapsedSections = useMemo(() => new Set(parseCollapsedSidebarSections(raw)), [raw]);

    const isCollapsed = useCallback(
        (sectionId: SidebarSectionId) => collapsedSections.has(sectionId),
        [collapsedSections],
    );
    const setCollapsed = useCallback(
        (sectionId: SidebarSectionId, collapsed: boolean) => {
            if (!canPersist()) {
                volatileStates.delete(key);
                return;
            }
            const next = new Set(parseCollapsedSidebarSections(readStoredState(key)));
            if (collapsed) next.add(sectionId);
            else next.delete(sectionId);
            const serialized = serializeCollapsedSidebarSections(next);
            try {
                window.localStorage.setItem(key, serialized);
                volatileStates.delete(key);
            } catch {
                volatileStates.set(key, serialized);
            }
            window.dispatchEvent(new Event(eventName));
        },
        [canPersist, eventName, key],
    );

    return { isCollapsed, setCollapsed };
}
