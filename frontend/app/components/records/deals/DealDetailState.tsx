'use client';

import { createContext, useContext, useState, type ReactNode } from 'react';
import type { Deal } from '@/app/lib/types';
import { latestDealSnapshot } from './dealEditConflict';

type DealDetailState = { deal: Deal; publish: (saved: Deal) => void };
const DealDetailContext = createContext<DealDetailState | null>(null);

/** Retains the newest server or acknowledged snapshot within one workspace and deal. */
export function DealDetailProvider({ initial, children }: { initial: Deal; children: ReactNode }) {
    const [snapshot, setSnapshot] = useState({ source: initial, retained: initial });
    const sameScope = initial.id === snapshot.source.id && initial.workspaceId === snapshot.source.workspaceId;
    const deal = sameScope ? latestDealSnapshot(initial, snapshot.retained) : initial;
    if (snapshot.source !== initial) {
        setSnapshot({ source: initial, retained: deal });
    }
    const publish = (next: Deal) => {
        setSnapshot((current) => {
            const scope = current.source;
            if (next.id !== scope.id || (scope.workspaceId !== undefined && next.workspaceId !== scope.workspaceId)) return current;
            return { ...current, retained: latestDealSnapshot(current.retained, next) };
        });
    };
    return <DealDetailContext.Provider value={{ deal, publish }}>{children}</DealDetailContext.Provider>;
}

/** Reads the current detail snapshot; reusable menus outside a detail retain their local state. */
export function useDealDetail() {
    return useContext(DealDetailContext);
}

/** Reads an outcome display's required page snapshot. */
export function useDealDetailRecord(): Deal {
    const context = useDealDetail();
    if (context === null) throw new Error('Deal detail values require DealDetailProvider');
    return context.deal;
}
