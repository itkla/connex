'use client';

import { createContext, useContext, useState, type ReactNode } from 'react';
import type { Deal } from '@/app/lib/types';
import { latestDealSnapshot } from './dealEditConflict';

type DealDetailState = { deal: Deal; publish: (saved: Deal) => void };
const DealDetailContext = createContext<DealDetailState | null>(null);

/** Shares acknowledged outcomes until a server refresh reaches their version. Scope by workspace and deal. */
export function DealDetailProvider({ initial, children }: { initial: Deal; children: ReactNode }) {
    const [saved, setSaved] = useState<Deal | null>(null);
    const deal = latestDealSnapshot(initial, saved);
    const publish = (next: Deal) => {
        if (next.id !== initial.id || (initial.workspaceId !== undefined && next.workspaceId !== initial.workspaceId)) return;
        setSaved((current) => latestDealSnapshot(latestDealSnapshot(initial, current), next));
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
