"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";

import type { SavedView } from "@/app/lib/types";
import { getSavedViewPins } from "@/app/lib/api";
import { subscribeToSavedViewMutations } from "@/app/lib/saved-view-events";
import { useWorkspace } from "@/app/hooks/useWorkspace";

type PinnedViewsContextValue = {
    /** The current user's pinned saved views for the active workspace, in pin order. */
    pins: SavedView[];
};

const PinnedViewsContext = createContext<PinnedViewsContextValue | null>(null);

/**
 * Loads and keeps fresh the current user's pinned saved views for the active workspace, so the sidebar
 * and command palette can surface them as shortcuts. Refetches when a saved view's pin state changes
 * (via {@link subscribeToSavedViewMutations}) and resets on a workspace switch so pins never leak across
 * workspaces. Mount it inside {@code ActionProvider} so the palette bridge beneath it can register
 * navigation actions from the same data.
 */
export function PinnedViewsProvider({ children }: { children: ReactNode }) {
    const { activeWorkspaceId } = useWorkspace();
    const [pins, setPins] = useState<SavedView[]>([]);
    const workspaceRef = useRef(activeWorkspaceId);

    const load = useCallback(() => {
        let active = true;
        getSavedViewPins()
            .then((views) => { if (active) setPins(views); })
            .catch(() => { if (active) setPins([]); });
        return () => { active = false; };
    }, []);

    useEffect(() => {
        if (workspaceRef.current !== activeWorkspaceId) {
            workspaceRef.current = activeWorkspaceId;
            setPins([]);
        }
        return load();
    }, [activeWorkspaceId, load]);

    useEffect(() => subscribeToSavedViewMutations(() => { load(); }), [load]);

    const value = useMemo<PinnedViewsContextValue>(() => ({ pins }), [pins]);
    return <PinnedViewsContext.Provider value={value}>{children}</PinnedViewsContext.Provider>;
}

/** The current user's pinned saved views. Throws if used outside {@link PinnedViewsProvider}. */
export function usePinnedViews(): PinnedViewsContextValue {
    const value = useContext(PinnedViewsContext);
    if (!value) throw new Error("usePinnedViews must be used within PinnedViewsProvider");
    return value;
}
