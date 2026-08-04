"use client";

import { useCallback, useMemo, useState } from "react";

import { savedViewConfigKey } from "@/app/lib/savedViewConfig";
import type { SavedView, SavedViewConfig } from "@/app/lib/types";

interface ExplicitSavedViewScope {
    id: number;
    configKey: string;
}

/** Tracks an explicitly applied saved view and derives whether it still matches the live browser state. */
export function useSavedViewScope(initialViews: SavedView[], currentConfig: SavedViewConfig) {
    const [explicitScope, setExplicitScope] = useState<ExplicitSavedViewScope | null>(null);
    const currentKey = useMemo(() => savedViewConfigKey(currentConfig), [currentConfig]);
    const activeSavedViewId = useMemo(() => {
        if (explicitScope) return explicitScope.configKey === currentKey ? explicitScope.id : null;
        return initialViews.find((view) => savedViewConfigKey(view.config) === currentKey)?.id ?? null;
    }, [currentKey, explicitScope, initialViews]);
    const setActiveSavedView = useCallback((config: SavedViewConfig, savedViewId: number | null) => {
        setExplicitScope(savedViewId === null
            ? null
            : { id: savedViewId, configKey: savedViewConfigKey(config) });
    }, []);

    return { activeSavedViewId, setActiveSavedView };
}
