'use client';

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

import { useActions } from '@/app/hooks/useActions';
import { useWorkspace } from '@/app/hooks/useWorkspace';

/** Desktop sidebar presentation: full labeled navigation, or a narrow icon rail. */
export type SidebarMode = 'expanded' | 'rail';

const MODE_PREFIX = 'connex:sidebar-mode:';

type SidebarModeContextValue = {
    mode: SidebarMode;
    setMode: (next: SidebarMode) => void;
    toggle: () => void;
};

const SidebarModeContext = createContext<SidebarModeContextValue | null>(null);

function modeStorageKey(userId: number | null, workspaceId: number | null): string {
    return `${MODE_PREFIX}${userId ?? 'anon'}:${workspaceId ?? 'none'}`;
}

function isMode(value: string | null): value is SidebarMode {
    return value === 'expanded' || value === 'rail';
}

/**
 * Holds the desktop sidebar mode so the container ({@link ContentShell}) and the sidebar itself stay in
 * sync, persisting it per user + workspace (localStorage) so the choice survives sessions without leaking
 * across accounts on a shared browser. Read after mount to avoid a hydration mismatch; defaults to expanded.
 */
export function SidebarModeProvider({ children }: { children: ReactNode }) {
    const { context } = useActions();
    const { activeWorkspaceId } = useWorkspace();
    const key = modeStorageKey(context.user?.id ?? null, activeWorkspaceId);
    const [mode, setModeState] = useState<SidebarMode>('expanded');

    useEffect(() => {
        let stored: string | null = null;
        try {
            stored = window.localStorage.getItem(key);
        } catch {
            return;
        }
        const next: SidebarMode = isMode(stored) ? stored : 'expanded';
        // eslint-disable-next-line react-hooks/set-state-in-effect
        setModeState((prev) => (prev === next ? prev : next));
    }, [key]);

    const setMode = useCallback(
        (next: SidebarMode) => {
            setModeState(next);
            try {
                window.localStorage.setItem(key, next);
            } catch {}
        },
        [key],
    );

    const toggle = useCallback(() => setMode(mode === 'rail' ? 'expanded' : 'rail'), [mode, setMode]);

    const value = useMemo(() => ({ mode, setMode, toggle }), [mode, setMode, toggle]);

    return <SidebarModeContext.Provider value={value}>{children}</SidebarModeContext.Provider>;
}

/** Reads the desktop sidebar mode; falls back to expanded when rendered outside the provider. */
export function useSidebarMode(): SidebarModeContextValue {
    const ctx = useContext(SidebarModeContext);
    return ctx ?? { mode: 'expanded', setMode: () => {}, toggle: () => {} };
}
