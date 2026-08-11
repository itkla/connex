"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";

import type { SavedView } from "@/app/lib/types";
import { getSavedViewPins } from "@/app/lib/api";
import { subscribeToSavedViewMutations } from "@/app/lib/saved-view-events";
import { useWorkspace } from "@/app/hooks/useWorkspace";

type PinnedViewsContextValue = {
    /** The current user's pinned saved views for the active workspace, in pin order. */
    pins: SavedView[];
    /** Whether the current workspace's pins are loading, resolved, or unavailable. */
    status: "loading" | "ready" | "unavailable";
    /** Retries the current workspace's pin read. */
    reload: () => Promise<void>;
};

const PinnedViewsContext = createContext<PinnedViewsContextValue | null>(null);
const EMPTY_PINS: SavedView[] = [];

/**
 * Loads and keeps fresh the current user's pinned saved views for the active workspace, so the sidebar
 * and command palette can surface them as shortcuts. Refetches when a saved view's pin state changes
 * (via {@link subscribeToSavedViewMutations}) and resets on a workspace switch so pins never leak across
 * workspaces. Mount it inside {@code ActionProvider} so the palette bridge beneath it can register
 * navigation actions from the same data.
 */
export function PinnedViewsProvider({ children }: { children: ReactNode }) {
    const { activeWorkspaceId } = useWorkspace();
    const [state, setState] = useState<{
        workspaceId: number | null;
        pins: SavedView[];
        status: PinnedViewsContextValue["status"];
    }>(() => ({ workspaceId: activeWorkspaceId, pins: [], status: "loading" }));
    const loadGenerationRef = useRef(0);

    const load = useCallback((): Promise<void> => {
        const generation = ++loadGenerationRef.current;
        return getSavedViewPins().then((views) => {
            if (loadGenerationRef.current !== generation) return;
            setState({ workspaceId: activeWorkspaceId, pins: views, status: "ready" });
        }).catch(() => {
            if (loadGenerationRef.current !== generation) return;
            setState((current) => ({
                workspaceId: activeWorkspaceId,
                pins: current.workspaceId === activeWorkspaceId ? current.pins : [],
                status: "unavailable",
            }));
        });
    }, [activeWorkspaceId]);

    useEffect(() => {
        void load();
        return () => { loadGenerationRef.current += 1; };
    }, [load]);

    useEffect(() => subscribeToSavedViewMutations(() => { void load(); }), [load]);

    const pins = state.workspaceId === activeWorkspaceId ? state.pins : EMPTY_PINS;
    const status = state.workspaceId === activeWorkspaceId ? state.status : "loading";
    const value = useMemo<PinnedViewsContextValue>(
        () => ({ pins, status, reload: load }),
        [pins, status, load],
    );
    return <PinnedViewsContext.Provider value={value}>{children}</PinnedViewsContext.Provider>;
}

/** The current user's pinned saved views. Throws if used outside {@link PinnedViewsProvider}. */
export function usePinnedViews(): PinnedViewsContextValue {
    const value = useContext(PinnedViewsContext);
    if (!value) throw new Error("usePinnedViews must be used within PinnedViewsProvider");
    return value;
}
