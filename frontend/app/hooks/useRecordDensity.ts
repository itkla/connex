'use client';

import { useCallback, useEffect, useState } from 'react';

import { useActions } from '@/app/hooks/useActions';
import { useWorkspace } from '@/app/hooks/useWorkspace';

/** Row density for the shared record tables. */
export type RowDensity = 'comfortable' | 'compact';

const DENSITY_PREFIX = 'connex:density:';

function densityStorageKey(userId: number | null, workspaceId: number | null): string {
    return `${DENSITY_PREFIX}${userId ?? 'anon'}:${workspaceId ?? 'none'}`;
}

function isDensity(value: string | null): value is RowDensity {
    return value === 'comfortable' || value === 'compact';
}

/**
 * Reads and persists the user's record-table row density, scoped to the active user + workspace so the
 * preference survives sessions (localStorage) without leaking across accounts on a shared browser. The
 * value is read after mount to avoid a hydration mismatch; it defaults to `comfortable`.
 */
export function useRecordDensity(): { density: RowDensity; setDensity: (next: RowDensity) => void } {
    const { context } = useActions();
    const { activeWorkspaceId } = useWorkspace();
    const key = densityStorageKey(context.user?.id ?? null, activeWorkspaceId);
    const [density, setDensityState] = useState<RowDensity>('comfortable');

    useEffect(() => {
        let stored: string | null = null;
        try {
            stored = window.localStorage.getItem(key);
        } catch {
            return;
        }
        const next: RowDensity = isDensity(stored) ? stored : 'comfortable';
        // eslint-disable-next-line react-hooks/set-state-in-effect
        setDensityState((prev) => (prev === next ? prev : next));
    }, [key]);

    const setDensity = useCallback(
        (next: RowDensity) => {
            setDensityState(next);
            try {
                window.localStorage.setItem(key, next);
            } catch {}
        },
        [key],
    );

    return { density, setDensity };
}
